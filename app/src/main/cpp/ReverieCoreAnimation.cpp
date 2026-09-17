/*
 * ReverieCore - animation domain (frame-by-frame: layers as tracks)
 *
 * 实现 ReverieCore.h 中的动画 API。数据完全落在 Krita 原生对象上:
 *   - 每条轨道 = 一个图层, 帧容器 = 该图层 paint device 上的
 *     KisRasterKeyframeChannel (每个关键帧一份 KisPaintDevice, 共享瓦片)
 *   - 当前时间 / 帧率 / 播放范围 = KisImageAnimationInterface
 * 因此投影失效、瓦片回收、KRA 持久化全部复用引擎既有机制, 这里只做
 * 参数搬运与边界保护。
 *
 * 线程约束: 本文件所有函数都由 Kotlin 侧单 reverie-render 线程串行调用
 * (与文档其余部分一致), 内部不额外加锁。
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCore.h"
#include "ReverieCoreInternal.h"
#include "ReverieCoreInbetween.h"

#include <kis_image.h>
#include <kis_image_animation_interface.h>
#include <kis_node.h>
#include <kis_paint_layer.h>
#include <kis_paint_device.h>
#include <kis_keyframe_channel.h>
#include <kis_raster_keyframe_channel.h>
#include <kis_time_span.h>
#include <kis_onion_skin_compositor.h>
#include <kis_image_config.h>
#include <kis_undo_adapter.h>
#include <kundo2command.h>
#include <kundo2magicstring.h>

#include <QSet>
#include <QVector>
#include <QHash>
#include <QImage>
#include <QPainter>
#include <algorithm>

namespace {

KisNode *nodeAtIndex(const QVector<ReverieCore::LayerEntry> &layers, int index)
{
    if (index < 0 || index >= layers.size()) return nullptr;
    return layers.at(index).node;
}

// 图层索引 -> 栅格关键帧通道。
//
// 只有 KisPaintLayer 重写了 requestKeyframeChannel 支持 Raster:
// KisBaseNode::requestKeyframeChannel 对其余 id 一律返回 nullptr
// (kis_base_node.cpp:469), 所以 group / 调整 / 填充 / 矢量层会拿到空指针
// 而不是崩溃 —— 调用方只需判空。
//
// create=true 等价于 Krita 的"开启动画", 且是幂等的: getKeyframeChannel
// 先查后建, 已存在时直接返回, 不会重复触发
// KisPaintDevice::createKeyframeChannel 里的 Q_ASSERT。创建时 Krita 会自动
// 补一个 frame 0, 因此轨道永远至少有一帧。
KisRasterKeyframeChannel *rasterChannelOf(KisNode *node, bool create)
{
    if (!node) return nullptr;
    if (!dynamic_cast<KisPaintLayer *>(node)) return nullptr;
    KisKeyframeChannel *channel = node->getKeyframeChannel(KisKeyframeChannel::Raster.id(), create);
    return dynamic_cast<KisRasterKeyframeChannel *>(channel);
}

} // namespace

// 丢弃所有洋葱皮图层的缓存, 让下一帧重新合成。
//
// 为什么必须显式调: KisOnionSkinCache::checkCacheValid 的判据只有
//   currentTime / configSeqNo / channelHash
// 三样 (kis_onion_skin_cache.cpp:40), **帧内的像素改动不在其中**。因此在
// 第 3 帧上画一笔、视线停在第 4 帧时, 第 4 帧看到的仍是旧的洋葱皮 ——
// 表现为"改了前一帧, 当前帧的洋葱皮不跟着更新"。Krita 桌面端是在
// KisProcessingApplicator::Private::partB 里对全帧操作 flush 解决的
// (kis_processing_applicator.cpp:90), 逐帧作画的落笔路径不走那个类,
// 所以只能自己挂。
//
// **热路径专用**: 只 reset 缓存, 不动 extent。
// 曾经这里还附带 calculateFullExtent() + setDirty(), 那是 O(轨道关键帧数)
// 的全量扫描, 逐帧作画时轨道几百帧, 每笔收笔扫一遍直接把绘制拖掉帧。
// extent 的伸缩只在开/关洋葱皮时才需要 (见 configureOnionSkin), 内容变更
// 走不到"需要扩大脏区"这一步 —— setDirty 已经由笔画本身标记了。
void ReverieCore::flushOnionSkinCaches()
{
    for (int i = 0; i < m_layers.size(); ++i) {
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_layers[i].node);
        if (!pl || !pl->onionSkinEnabled()) continue;
        pl->flushOnionSkinCache();
    }
}

// 是否有任意图层开着洋葱皮 (调用方用来跳过无谓的 flush 开销)
bool ReverieCore::hasAnyOnionSkinLayer() const
{
    for (int i = 0; i < m_layers.size(); ++i) {
        const KisPaintLayer *pl = dynamic_cast<const KisPaintLayer *>(m_layers[i].node);
        if (pl && pl->onionSkinEnabled()) return true;
    }
    return false;
}

namespace {

// 轨道末尾之外的临时停放位置。重排时先把关键帧搬到这里, 再落位到目标,
// 避免"目标已占用"导致源数据被覆盖。
int parkingBaseFor(KisKeyframeChannel *channel, int count)
{
    int maxTime = -1;
    const QSet<int> times = channel->allKeyframeTimes();
    for (int t : times) {
        if (t > maxTime) maxTime = t;
    }
    return maxTime + count + 10;
}

// 三阶段"停车"重排: 复制到停放区 -> 删除原地 -> 复制到目标位置。
// 全部操作挂同一个 parentCmd, 因此是单步撤销。
bool rearrangeKeyframes(KisKeyframeChannel *channel,
                        const QVector<int> &oldTimes,
                        const QVector<int> &newTimes,
                        KUndo2Command *parentCmd)
{
    const int count = oldTimes.size();
    if (count != newTimes.size() || count == 0) return false;

    bool anyMoved = false;
    for (int i = 0; i < count; ++i) {
        if (oldTimes[i] != newTimes[i]) {
            anyMoved = true;
            break;
        }
    }
    if (!anyMoved) return false;

    const int parkingBase = parkingBaseFor(channel, count);

    // Phase 1: 全部搬到停放区 (含未移动的帧, 保持索引一致)
    for (int i = 0; i < count; ++i) {
        KisKeyframeChannel::copyKeyframe(channel, oldTimes[i], channel, parkingBase + i, parentCmd);
    }

    // Phase 2: 删除所有原始位置 (从后往前, 避免索引漂移)
    for (int i = count - 1; i >= 0; --i) {
        channel->removeKeyframe(oldTimes[i], parentCmd);
    }

    // Phase 3: 从停放区落到目标位置
    for (int i = 0; i < count; ++i) {
        KisKeyframeChannel::copyKeyframe(channel, parkingBase + i, channel, newTimes[i], parentCmd);
    }

    // Phase 4: 清理停放区
    for (int i = 0; i < count; ++i) {
        channel->removeKeyframe(parkingBase + i, parentCmd);
    }

    return true;
}

} // namespace

// ============================================================
// Document: 时间 / 帧率 / 播放范围
// ============================================================

bool ReverieCore::animationEnabled() const
{
    KisImageSP image = m_document;
    if (!image) return false;
    return image->animationInterface()->hasAnimation();
}

int ReverieCore::animationCurrentTime() const
{
    KisImageSP image = m_document;
    if (!image) return 0;
    return image->animationInterface()->currentTime();
}

void ReverieCore::setAnimationCurrentTime(int time, bool recordUndo)
{
    KisImageSP image = m_document;
    if (!image || time < 0) return;

    KisImageAnimationInterface *anim = image->animationInterface();
    if (!anim || anim->currentTime() == time) return;

    anim->requestTimeSwitchNonGUI(time, recordUndo);

    // 时间切换经 image 调度器异步生效, 必须在返回前收敛: 否则紧随其后的
    // renderToBuffer 会读到上一帧的投影 (按播放键会出现画面滞后一帧)
    if (!image->isIdle()) {
        image->waitForDone();
    }

    // 这里**不能** flush 洋葱皮缓存。
    //
    // 曾经在这加过 flushOnionSkinCaches(), 结果播放时每帧都全量重算洋葱皮
    // (每次都要 calculateFullExtent 遍历整条轨道的所有关键帧 + 丢弃缓存
    // 导致下一帧重新合成), 直接把播放和绘制拖到掉帧。
    //
    // 实际上也不需要: KisOnionSkinCache::checkCacheValid 已经把
    // currentTime / configSeqNo / channelHash 纳入判据, 时间切到另一段
    // 曝光区间时 identicalFrames 不再包含新时间, 缓存会自然失效。
    // 真正需要显式 flush 的只有"帧内像素变了"这一类 (落笔 / 填充 / 关键帧
    // 增删), 那些路径各自处理。
    //
    // 笔触叠加用的自持缓存是另一套判据 (时间/代际/结构), 这里必须显式作废,
    // 否则切帧后笔画一开始用的还是上一帧的洋葱皮。
    invalidateStrokeOnionCache();

    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
}

int ReverieCore::animationFramerate() const
{
    KisImageSP image = m_document;
    if (!image) return 24;
    return image->animationInterface()->framerate();
}

void ReverieCore::setAnimationFramerate(int fps)
{
    KisImageSP image = m_document;
    if (!image || fps <= 0 || fps > 240) return;
    image->animationInterface()->setFramerate(fps);
}

int ReverieCore::animationLength() const
{
    // 不用 animationInterface()->totalLength(): 它受播放范围影响, 而时间轴
    // 需要的是"内容实际覆盖多长"。直接遍历所有轨道的关键帧取最大值。
    int last = -1;
    for (int i = 0; i < m_layers.size(); ++i) {
        KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, i), false);
        if (!channel) continue;
        const QSet<int> times = channel->allKeyframeTimes();
        for (int t : times) {
            if (t > last) last = t;
        }
    }
    return last + 1;
}

void ReverieCore::animationPlaybackRange(int *start, int *end) const
{
    KisImageSP image = m_document;
    if (!image) {
        if (start) *start = 0;
        if (end) *end = 0;
        return;
    }
    const KisTimeSpan range = image->animationInterface()->activePlaybackRange();
    if (start) *start = range.start();
    if (end) *end = range.end();
}

void ReverieCore::setAnimationPlaybackRange(int start, int end)
{
    KisImageSP image = m_document;
    if (!image || start < 0 || end < start) return;
    // KisTimeSpan 的 (start, end) 构造函数是私有的, 必须走工厂方法
    image->animationInterface()->setActivePlaybackRange(KisTimeSpan::fromTimeToTime(start, end));
}

// ============================================================
// 洋葱皮
// ============================================================

void ReverieCore::configureOnionSkin(bool enabled, int prev, int next, int maxOpacity,
                                    int tintFactor, int tintBackwardArgb, int tintForwardArgb)
{
    KisImageSP image = m_document;
    if (!image) return;

    prev = qBound(0, prev, 10);
    next = qBound(0, next, 10);
    maxOpacity = qBound(0, maxOpacity, 255);
    tintFactor = qBound(0, tintFactor, 100);

    // "开了但前后帧数都是 0" 等价于没开 —— 否则下面 qMax(1, skins) 会把
    // numberOfSkins 抬到 1, 于是用户看到一帧莫名其妙的洋葱皮且关不掉
    const bool effective = enabled && (prev > 0 || next > 0);

    // 全局配置: 帧数/每帧状态与透明度。index 0 是当前帧, 作为整体缩放系数。
    // 前后帧数不对称时取 max, 单侧多余帧用 state=false 关掉。
    // 透明度从 maxOpacity 向远帧线性衰减 —— 最近帧也不能全不透明, 否则洋葱皮
    // 会盖住当前帧正在画的内容
    //
    // 注意: KisImageConfig 是**写盘**的 (kritarc)。这里每次都把 0..10 全部
    // 写死, 是为了覆盖掉上一次会话或桌面 Krita 留下的旧条目 —— 只写当前用
    // 到的档位会让 onionSkinOpacity_5 之类的残留值在下一次扩大帧数时突然
    // 生效, 表现为"改了前后帧数但透明度乱七八糟"
    {
        KisImageConfig config(true);
        const int skins = qMax(prev, next);
        config.setNumberOfOnionSkins(qMax(1, skins));
        // tintFactor 的语义是"着色层叠在帧上的不透明度": 0 = 不着色, 255 = 纯色块。
        // 传入的是百分比 (0~100), 按 255 折算
        config.setOnionSkinTintFactor(tintFactor * 255 / 100);
        config.setOnionSkinState(0, true);
        config.setOnionSkinOpacity(0, 255);
        // 前后帧着色色板。Krita 默认 红=过去 / 绿=未来, 用颜色区分时间方向;
        // 传 0 表示"不改", 保留上一次写入的值 (老调用方零侵入)。
        if (tintBackwardArgb != 0) {
            config.setOnionSkinTintColorBackward(QColor::fromRgb(QRgb(tintBackwardArgb)));
        }
        if (tintForwardArgb != 0) {
            config.setOnionSkinTintColorForward(QColor::fromRgb(QRgb(tintForwardArgb)));
        }
        for (int i = 0; i < 10; ++i) {
            const bool bOn = effective && i < prev;
            const bool fOn = effective && i < next;
            config.setOnionSkinState(-(i + 1), bOn);
            config.setOnionSkinState(i + 1, fOn);
            // 衰减: 第 i+1 近的帧拿到 maxOpacity * (n-i) / n
            // 最近帧 == maxOpacity, 最远帧 == maxOpacity/n (n=1 时就是 maxOpacity)
            const int bOp = bOn ? maxOpacity * (prev - i) / qMax(1, prev) : 0;
            const int fOp = fOn ? maxOpacity * (next - i) / qMax(1, next) : 0;
            config.setOnionSkinOpacity(-(i + 1), bOp);
            config.setOnionSkinOpacity(i + 1, fOp);
        }
    }
    // 重读配置并广播 sigOnionSkinChanged (已连接的图层会自行刷新缓存)
    KisOnionSkinCompositor::instance()->configChanged();

    // 笔触叠加缓存自己判 configSeqNo, 但"开关从关到开"时 seq 可能刚好撞上,
    // 这里显式作废最稳妥
    invalidateStrokeOnionCache();

    // 记住全局开关的生效值, 供 applyOnionSkinGate 在换层/结构重排时重放
    m_onionSkinActive = effective;
    applyOnionSkinGate(true);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
}

// 把洋葱皮"渲染门"落实到各图层。per-layer node property (onionskin) 是
// Krita 投影路径 (copyOriginalToProjection) 与自研笔触叠加路径
// (compositeLayersRange / strokeOnionProjection) 共同的开关, 所以门在这
// 一处收紧, 两条渲染路径同时生效。目标值 = 全局生效值 && 未被播放抑制
// && 是当前选中轨道 —— 洋葱皮只渲染用户正在画的那条轨道, 播放期间全部
// 压掉, 其他轨道不出邻帧叠影。
void ReverieCore::applyOnionSkinGate(bool refreshCaches)
{
    for (int i = 0; i < m_layers.size(); ++i) {
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_layers[i].node);
        if (!pl) continue;
        const bool target = m_onionSkinActive && !m_onionSkinSuppressed && (i == m_currentLayer);
        const bool wasEnabled = pl->onionSkinEnabled();
        if (wasEnabled != target) {
            pl->setOnionSkinEnabled(target);
            // configChanged() 只让缓存**在下次校验时**失效, 而
            // KisOnionSkinCache::checkCacheValid 的判据是 currentTime /
            // configSeqNo / channelHash 三者 —— 图层开关本身不在其中。
            // 不显式 reset 的话, 从"关"切回"开"会直接命中上一次留下的空缓存
            // (这正是"洋葱皮关掉再开才生效"的根因)。
            pl->flushOnionSkinCache();
            // 扩大/缩小脏区: 开启时把洋葱皮区域画出来, 关闭时擦掉残留。
            //
            // 用 calculateExtent 而不是 calculateFullExtent: 前者只扫前后
            // numberOfSkins 帧, 正好是洋葱皮实际会合成的范围; 后者要遍历整条
            // 轨道的所有关键帧求并集, 逐帧作画时轨道几百帧, 开启一次就是一次
            // O(N) 扫全轨道 —— 用户拖滑块调参数时会连续触发, 是实打实的卡顿源。
            pl->setDirty(KisOnionSkinCompositor::instance()->calculateExtent(pl->paintDevice()));
        } else if (refreshCaches && target) {
            // 开关没动、但配置内容 (帧数/透明度/着色) 变了: 缓存与脏区照刷
            pl->flushOnionSkinCache();
            pl->setDirty(KisOnionSkinCompositor::instance()->calculateExtent(pl->paintDevice()));
        }
    }
}

bool ReverieCore::anyLayerOnionSkin() const
{
    for (int i = 0; i < m_layers.size(); ++i) {
        const KisPaintLayer *pl = dynamic_cast<const KisPaintLayer *>(m_layers[i].node);
        if (pl && pl->onionSkinEnabled()) return true;
    }
    return false;
}

void ReverieCore::setOnionSkinSuppressed(bool suppressed)
{
    if (m_onionSkinSuppressed == suppressed) return;
    m_onionSkinSuppressed = suppressed;

    if (suppressed) {
        // 记录压掉前的逻辑状态, 抑制窗口内的序列化 (onionSkinLogicalEnabled)
        // 靠它。只记录不搬运 —— 每层 property 的实际压掉交给下面的
        // applyOnionSkinGate (它感知 m_onionSkinSuppressed, 目标值全 false)。
        // 开启时清掉历史残留 (换文档后旧图层指针的条目), 从头记录。
        m_onionSuppressedPrev.clear();
        for (int i = 0; i < m_layers.size(); ++i) {
            KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_layers[i].node);
            if (pl) m_onionSuppressedPrev.insert(pl, pl->onionSkinEnabled());
        }
    } else {
        // 恢复不逐层按表还原: 播放期间用户可能换过轨道, 按表还原会把门留在
        // 旧层上。清表后由 applyOnionSkinGate 按"当前层 + 全局开关"重放。
        m_onionSuppressedPrev.clear();
    }

    invalidateStrokeOnionCache();
    applyOnionSkinGate(false);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
}

bool ReverieCore::onionSkinLogicalEnabled(const KisPaintLayer *pl) const
{
    if (m_onionSkinSuppressed) {
        // 压掉期间 node property 已是 false, 返回压掉前的逻辑状态
        return m_onionSuppressedPrev.value(const_cast<KisPaintLayer *>(pl), false);
    }
    return pl->onionSkinEnabled();
}

void ReverieCore::onionSkinTintColors(int *backwardArgb, int *forwardArgb) const
{
    KisImageConfig config(true);
    const QRgb b = config.onionSkinTintColorBackward().rgb();
    const QRgb f = config.onionSkinTintColorForward().rgb();
    if (backwardArgb) *backwardArgb = int(0xFF000000u | quint32(b));
    if (forwardArgb) *forwardArgb = int(0xFF000000u | quint32(f));
}

int ReverieCore::onionSkinTintFactor() const
{
    KisImageConfig config(true);
    return qBound(0, config.onionSkinTintFactor(), 255);
}

// ============================================================
// 导入
// ============================================================

bool ReverieCore::importKeyframeFromBitmap(int layerIndex, int time, int w, int h, void *pixels, int stride)
{
    KisImageSP image = m_document;
    if (!image || layerIndex < 0 || layerIndex >= m_layers.size() || !pixels || w <= 0 || h <= 0) {
        return false;
    }
    KisRasterKeyframeChannel *channel = rasterChannelOf(m_layers[layerIndex].node, true);
    if (!channel) return false;

    if (time > 0 && !channel->keyframeAt(time)) {
        if (!addKeyframe(layerIndex, time)) return false;
    }
    KisRasterKeyframeSP key = channel->keyframeAt<KisRasterKeyframe>(time);
    if (!key) return false;

    // Android Bitmap 锁出的内存是 ARGB_8888 premultiplied = RGBA8888_Premultiplied 字节序
    const QImage img(static_cast<const uchar *>(pixels), w, h, stride, QImage::Format_RGBA8888_Premultiplied);
    KisPaintDeviceSP tmp = new KisPaintDevice(channel->paintDevice()->colorSpace());
    tmp->convertFromQImage(img, nullptr);
    channel->paintDevice()->framesInterface()->uploadFrame(key->frameID(), tmp);
    channel->paintDevice()->setDirty();

    bumpKeyframeThumbGen();
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    return true;
}

void ReverieCore::storeRevAsset(const QString &name, const QByteArray &data)
{
    if (name.isEmpty() || data.isEmpty()) return;
    m_revAssets[name] = data;
}

QVector<QString> ReverieCore::revAssetNames() const
{
    QVector<QString> out;
    out.reserve(m_revAssets.size());
    for (auto it = m_revAssets.constBegin(); it != m_revAssets.constEnd(); ++it) {
        out.append(it.key());
    }
    return out;
}

QByteArray ReverieCore::revAssetBytes(const QString &name) const
{
    return m_revAssets.value(name);
}

// ============================================================
// Track: 图层即轨道
// ============================================================

bool ReverieCore::layerAnimated(int index) const
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, index), false);
    return channel != nullptr && channel->keyframeCount() > 0;
}

bool ReverieCore::layerAnimatable(int index) const
{
    if (index < 0 || index >= m_layers.size()) return false;
    const ReverieCore::LayerEntry &e = m_layers.at(index);
    // 背景层(白底、alpha 锁定)与组/调整/填充/矢量层不参与逐帧动画
    if (e.background || e.isGroup) return false;
    return dynamic_cast<KisPaintLayer *>(e.node) != nullptr;
}

bool ReverieCore::enableLayerAnimation(int index)
{
    if (!layerAnimatable(index)) return false;

    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, index), true);
    if (!channel) return false;

    // 建通道时 Krita 已自动补 frame 0, 轨道不会处于"零帧"状态
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    return true;
}

// ============================================================
// Keyframe: 帧
// ============================================================

bool ReverieCore::hasKeyframe(int layerIndex, int time) const
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    return channel != nullptr && channel->keyframeAt(time);
}

int ReverieCore::keyframeCount(int layerIndex) const
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    return channel ? channel->keyframeCount() : 0;
}

QVector<int> ReverieCore::keyframeTimes(int layerIndex) const
{
    QVector<int> result;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel) return result;

    const QSet<int> times = channel->allKeyframeTimes();
    result.reserve(times.size());
    for (int t : times) {
        result.append(t);
    }
    std::sort(result.begin(), result.end());
    return result;
}

bool ReverieCore::addKeyframe(int layerIndex, int time)
{
    if (time < 0) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), true);
    if (!channel) return false;
    if (channel->keyframeAt(time)) return true;  // 幂等

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Add Keyframe"));
    channel->addKeyframe(time, cmd);
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    // 新建的是空白帧: 其它帧的缩略图不受影响, 不作全局失效。
    // 新帧自身没有缓存条目, UI 侧会自然渲染它 (引擎 miss -> 透明图)。
    return true;
}

bool ReverieCore::addDuplicateKeyframe(int layerIndex, int time)
{
    if (time < 0) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), true);
    if (!channel) return false;
    if (channel->keyframeAt(time)) return true;

    // 复制前一帧是逐帧作画最高频的动作 (续画 / 中割起点)
    const int srcTime = channel->previousKeyframeTime(time);

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Duplicate Keyframe"));
    if (srcTime >= 0 && channel->keyframeAt(srcTime)) {
        channel->cloneKeyframe(srcTime, time, cmd);  // 共享像素, 首次落笔才分叉
    } else {
        channel->addKeyframe(time, cmd);
    }
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    dirtyKeyframeThumb(layerIndex, time);  // 只有目标帧是新内容
    return true;
}

bool ReverieCore::removeKeyframe(int layerIndex, int time)
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel || !channel->keyframeAt(time)) return false;
    // 轨道至少保留一帧: 删空会让图层彻底失去内容
    if (channel->keyframeCount() <= 1) return false;

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Remove Keyframe"));
    channel->removeKeyframe(time, cmd);
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    // 精准作废被删帧: 引擎缓存清条目 + 发脏帧信号 (同帧号随后重建帧 /
    // UI 侧丢弃旧位图), 其它帧内容不变, 不作全局失效。
    dirtyKeyframeThumb(layerIndex, time);
    return true;
}

bool ReverieCore::copyKeyframe(int layerIndex, int fromTime, int toTime)
{
    if (fromTime == toTime || toTime < 0) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel || !channel->keyframeAt(fromTime)) return false;

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Copy Keyframe"));
    // 静态 copyKeyframe 生成独立像素副本 (与 cloneKeyframe 的共享像素相对)
    KisKeyframeChannel::copyKeyframe(channel, fromTime, channel, toTime, cmd);
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    dirtyKeyframeThumb(layerIndex, toTime);  // 源帧不变, 只有目标帧是新内容
    return true;
}

bool ReverieCore::cloneKeyframe(int layerIndex, int fromTime, int toTime)
{
    if (fromTime == toTime || toTime < 0) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel || !channel->keyframeAt(fromTime)) return false;

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Clone Keyframe"));
    channel->cloneKeyframe(fromTime, toTime, cmd);
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    dirtyKeyframeThumb(layerIndex, toTime);  // 共享像素: 源帧内容未变
    return true;
}

bool ReverieCore::moveKeyframe(int layerIndex, int fromTime, int toTime)
{
    if (fromTime == toTime || toTime < 0) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel || !channel->keyframeAt(fromTime)) return false;

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Move Keyframe"));

    // 拖拽覆盖语义: 目标位置已有帧时先删掉它
    if (channel->keyframeAt(toTime)) {
        channel->removeKeyframe(toTime, cmd);
    }

    // 同样走停车法, 避免移动到相邻位置时与原位置互相覆盖
    const int parking = parkingBaseFor(channel, 1);
    KisKeyframeChannel::copyKeyframe(channel, fromTime, channel, parking, cmd);
    channel->removeKeyframe(fromTime, cmd);
    KisKeyframeChannel::copyKeyframe(channel, parking, channel, toTime, cmd);
    channel->removeKeyframe(parking, cmd);

    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    // 移动语义: 原位置的帧没了, 目标位置内容更新 (拖拽覆盖时 toTime 旧帧
    // 一并作废)。两个位置都精准失效, 其余帧照常复用。
    dirtyKeyframeThumb(layerIndex, fromTime);
    dirtyKeyframeThumb(layerIndex, toTime);
    return true;
}

int ReverieCore::previousKeyframeTime(int layerIndex, int time) const
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    return channel ? channel->previousKeyframeTime(time) : -1;
}

int ReverieCore::nextKeyframeTime(int layerIndex, int time) const
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    return channel ? channel->nextKeyframeTime(time) : -1;
}

int ReverieCore::keyframeDuration(int layerIndex, int time) const
{
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel || !channel->keyframeAt(time)) return -1;

    const int nextTime = channel->nextKeyframeTime(time);
    if (nextTime < 0) return -1;  // 末帧: 持续到无穷
    return nextTime - time;
}

bool ReverieCore::setAllKeyframesDuration(int layerIndex, int duration)
{
    if (duration < 1) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel) return false;

    const QVector<int> oldTimes = keyframeTimes(layerIndex);
    const int count = oldTimes.size();
    if (count <= 1) return false;

    // 首帧保持原位, 后续按 duration 均匀铺开
    QVector<int> newTimes(count);
    newTimes[0] = oldTimes[0];
    for (int i = 1; i < count; ++i) {
        newTimes[i] = newTimes[i - 1] + duration;
    }

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Set Keyframe Duration"));
    if (!rearrangeKeyframes(channel, oldTimes, newTimes, cmd)) {
        delete cmd;  // 没有任何帧需要移动
        return false;
    }
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    bumpKeyframeThumbGen();
    return true;
}

bool ReverieCore::setSelectedKeyframesDuration(int layerIndex, const QVector<int> &selectedTimes, int duration)
{
    if (duration < 1) return false;
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel) return false;

    // 过滤出确实存在关键帧的选中项
    QVector<int> validTimes;
    validTimes.reserve(selectedTimes.size());
    for (int t : selectedTimes) {
        if (channel->keyframeAt(t)) validTimes.append(t);
    }
    std::sort(validTimes.begin(), validTimes.end());
    const int count = validTimes.size();
    if (count <= 1) return false;

    // 选中帧按 duration 重排; 区间内未选中的帧按原间距整体推到尾部,
    // 这样"一拍N"只影响选中范围, 不会吃掉后面已有的作画内容
    const int firstTime = validTimes.first();
    const int lastTime = validTimes.last();

    QVector<int> newTimes(count);
    newTimes[0] = firstTime;
    for (int i = 1; i < count; ++i) {
        newTimes[i] = newTimes[i - 1] + duration;
    }
    const int tailShift = newTimes.last() - lastTime;

    // 收集需要在重排后整体后移的非选中帧
    QVector<int> pushedTimes;
    const QSet<int> allTimes = channel->allKeyframeTimes();
    for (int t : allTimes) {
        if (t > firstTime && t <= lastTime) {
            bool selected = false;
            for (int v : validTimes) {
                if (v == t) {
                    selected = true;
                    break;
                }
            }
            if (!selected) pushedTimes.append(t);
        }
    }
    std::sort(pushedTimes.begin(), pushedTimes.end());

    // 组装完整重排表: 选中帧落位 + 被推挤帧后移 (从后往前处理避免目标冲突)
    QVector<int> allOld = validTimes;
    QVector<int> allNew = newTimes;
    if (tailShift > 0) {
        for (int i = pushedTimes.size() - 1; i >= 0; --i) {
            allOld.append(pushedTimes[i]);
            allNew.append(pushedTimes[i] + tailShift);
        }
    }

    KUndo2Command *cmd = new KUndo2Command(kundo2_noi18n("Set Selected Keyframe Duration"));
    if (!rearrangeKeyframes(channel, allOld, allNew, cmd)) {
        delete cmd;
        return false;
    }
    pushUndoCommand(cmd);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    bumpKeyframeThumbGen();
    return true;
}

// ============================================================
// 帧缩略图 (时间轴帧块内绘制)
// ============================================================

void ReverieCore::dirtyKeyframeThumb(int layerIndex, int time)
{
    const quint64 key = (quint64(quint32(layerIndex)) << 32) | quint32(time);
    m_keyframeThumbCache.remove(key);
    m_dirtyKeyframeThumbs.insert(key);
}

QVector<int> ReverieCore::takeDirtyKeyframeThumbs()
{
    QVector<int> out;
    out.reserve(m_dirtyKeyframeThumbs.size() * 2);
    for (quint64 key : qAsConst(m_dirtyKeyframeThumbs)) {
        out.append(int(quint32(key >> 32)));
        out.append(int(quint32(key & 0xFFFFFFFFULL)));
    }
    m_dirtyKeyframeThumbs.clear();
    return out;
}

bool ReverieCore::renderKeyframeThumb(
    int layerIndex, int time, int w, int h, void *dstPixels, int dstStride)
{
    if (!m_document || layerIndex < 0 || layerIndex >= m_layers.size()
        || !dstPixels || w <= 0 || h <= 0) {
        return false;
    }
    if (m_layers[layerIndex].nodeType == NodeTypeAdjustment) {
        return false;
    }

    // 缓存: (图层, 帧号) -> 上一次生成的小图。代际号在整个文档内容变化时
    // 自增 (见 keyframeThumbGen), 因此只比较代际与尺寸, 不做逐像素比对。
    const quint64 key = (quint64(quint32(layerIndex)) << 32) | quint32(time);
    KeyframeThumbCache &cache = m_keyframeThumbCache[key];
    if (cache.gen == m_keyframeThumbGen && cache.img.size() == QSize(w, h)) {
        const int copyH = qMin(h, cache.img.height());
        for (int y = 0; y < copyH; ++y) {
            memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
                   cache.img.constScanLine(y), size_t(w) * 4);
        }
        return true;
    }

    QImage out(w, h, QImage::Format_RGBA8888);
    out.fill(Qt::transparent);

    // 取该图层指定时刻的关键帧内容。writeToDevice 会把目标关键帧拷进
    // 一块独立设备, 不触碰文档 currentTime, 所以画布当前帧不会被带偏。
    KisRasterKeyframeChannel *channel =
        rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (channel && time >= 0 && channel->keyframeAt(time)) {
        KisPaintDeviceWSP srcDevice = channel->paintDevice();
        const KoColorSpace *cs = srcDevice ? srcDevice->colorSpace()
                                           : m_document->colorSpace();
        if (cs) {
            // 临时设备接收该帧内容, 不污染通道自身的活动帧
            KisPaintDeviceSP tmp = new KisPaintDevice(cs);
            channel->writeToDevice(time, tmp);

            // 与图层缩略图同风格: 只渲染该帧已绘制的区域 (精确内容 bounds 与
            // 画布的交集), 缩放到整个缩略图尺寸, 保持长宽比并居中对齐。
            // 空白帧 (无有效绘制内容) 保持完全透明。
            const QRect canvasRect(0, 0, m_docWidth, m_docHeight);
            const QRect contentBounds = tmp->exactBounds().intersected(canvasRect);
            if (!contentBounds.isEmpty()) {
                const QImage thumb = tmp->createThumbnail(
                    w, h, Qt::KeepAspectRatio, KisThumbnailBoundsMode::Precise);
                if (!thumb.isNull()) {
                    QPainter p(&out);
                    p.drawImage(
                        QPointF((w - thumb.width()) / 2.0,
                                (h - thumb.height()) / 2.0),
                        thumb);
                    p.end();
                }
            }
        }
    }

    cache.img = out;
    cache.gen = m_keyframeThumbGen;

    const int copyH = qMin(h, out.height());
    for (int y = 0; y < copyH; ++y) {
        memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
               out.constScanLine(y), size_t(w) * 4);
    }
    return true;
}

// ============================================================
// 关键帧色标与末帧曝光保持
// ============================================================

namespace {

class KeyframeTagCommand : public KUndo2Command {
public:
    KeyframeTagCommand(ReverieCore *core, int layer, int time, int oldTag, int newTag)
        : KUndo2Command(kundo2_noi18n("Set Keyframe Tag")), m_core(core), m_layer(layer), m_time(time), m_oldTag(oldTag), m_newTag(newTag) {}
    void redo() override { m_core->loadKeyframeTag(m_layer, m_time, m_newTag); }
    void undo() override { m_core->loadKeyframeTag(m_layer, m_time, m_oldTag); }
private:
    ReverieCore *m_core;
    int m_layer, m_time, m_oldTag, m_newTag;
};

class LastFrameHoldCommand : public KUndo2Command {
public:
    LastFrameHoldCommand(ReverieCore *core, int layer, int oldHold, int newHold)
        : KUndo2Command(kundo2_noi18n("Set Last Frame Hold")), m_core(core), m_layer(layer), m_oldHold(oldHold), m_newHold(newHold) {}
    void redo() override { m_core->loadLastFrameHold(m_layer, m_newHold); }
    void undo() override { m_core->loadLastFrameHold(m_layer, m_oldHold); }
private:
    ReverieCore *m_core;
    int m_layer, m_oldHold, m_newHold;
};

} // namespace

int ReverieCore::keyframeTag(int layerIndex, int time) const
{
    const quint64 key = (quint64(quint32(layerIndex)) << 32) | quint32(time);
    return m_keyframeTags.value(key, 0);
}

void ReverieCore::setKeyframeTag(int layerIndex, int time, int tag)
{
    const quint64 key = (quint64(quint32(layerIndex)) << 32) | quint32(time);
    const int oldTag = m_keyframeTags.value(key, 0);
    if (oldTag == tag) return;
    KeyframeTagCommand *cmd = new KeyframeTagCommand(this, layerIndex, time, oldTag, tag);
    m_keyframeTags[key] = tag;
    pushUndoCommand(cmd);
}

void ReverieCore::loadKeyframeTag(int layerIndex, int time, int tag)
{
    const quint64 key = (quint64(quint32(layerIndex)) << 32) | quint32(time);
    if (tag == 0) {
        m_keyframeTags.remove(key);
    } else {
        m_keyframeTags[key] = tag;
    }
}

int ReverieCore::lastFrameHold(int layerIndex) const
{
    return m_lastFrameHold.value(layerIndex, 1);
}

void ReverieCore::setLastFrameHold(int layerIndex, int hold, bool recordUndo)
{
    if (layerIndex < 0 || hold < 1) return;
    const int oldHold = m_lastFrameHold.value(layerIndex, 1);
    if (oldHold == hold) return;

    if (recordUndo) {
        LastFrameHoldCommand *cmd = new LastFrameHoldCommand(this, layerIndex, oldHold, hold);
        m_lastFrameHold[layerIndex] = hold;
        pushUndoCommand(cmd);
    } else {
        m_lastFrameHold[layerIndex] = hold;
    }
}

void ReverieCore::loadLastFrameHold(int layerIndex, int hold)
{
    m_lastFrameHold[layerIndex] = qMax(1, hold);
}

// ============================================================
// 自动中割 (Auto In-betweening)
// ============================================================

bool ReverieCore::generateInbetween(int layerIndex, int timeA, int timeB, int targetTime, float t,
                                    qreal epsilon, int blurPasses, int denoiseArea)
{
    if (!m_document || layerIndex < 0 || layerIndex >= m_layers.size()
        || timeA < 0 || timeB < 0 || targetTime < 0 || timeA == timeB) {
        return false;
    }
    KisRasterKeyframeChannel *channel = rasterChannelOf(nodeAtIndex(m_layers, layerIndex), false);
    if (!channel || !channel->keyframeAt(timeA) || !channel->keyframeAt(timeB)) {
        return false;
    }

    KisPaintDeviceWSP srcDev = channel->paintDevice();
    const KoColorSpace *cs = srcDev ? srcDev->colorSpace() : m_document->colorSpace();
    if (!cs) return false;

    // 提取两帧内容转换为 QImage
    KisPaintDeviceSP devA = new KisPaintDevice(cs);
    channel->writeToDevice(timeA, devA);
    const QImage imgA = devA->convertToQImage(nullptr, 0, 0, m_docWidth, m_docHeight);

    KisPaintDeviceSP devB = new KisPaintDevice(cs);
    channel->writeToDevice(timeB, devB);
    const QImage imgB = devB->convertToQImage(nullptr, 0, 0, m_docWidth, m_docHeight);

    if (imgA.isNull() || imgB.isNull()) return false;

    ReverieInbetween::Options opts;
    opts.epsilon = epsilon;
    opts.blurPasses = blurPasses;
    opts.denoiseArea = denoiseArea;
    opts.strokeColor = m_brushColor;

    const QImage midImg = ReverieInbetween::interpolate(imgA, imgB, t, opts);
    if (midImg.isNull()) return false;

    KUndo2Command *macro = new KUndo2Command(kundo2_noi18n("Generate In-between"));
    if (channel->keyframeAt(targetTime)) {
        channel->removeKeyframe(targetTime, macro);
    }
    channel->addKeyframe(targetTime, macro);
    KisRasterKeyframeSP key = channel->keyframeAt<KisRasterKeyframe>(targetTime);
    if (!key) {
        delete macro;
        return false;
    }

    KisPaintDeviceSP tmp = new KisPaintDevice(cs);
    tmp->convertFromQImage(midImg, nullptr);
    channel->paintDevice()->framesInterface()->uploadFrame(key->frameID(), tmp);
    channel->paintDevice()->setDirty();

    pushUndoCommand(macro);
    markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    dirtyKeyframeThumb(layerIndex, targetTime);
    return true;
}

