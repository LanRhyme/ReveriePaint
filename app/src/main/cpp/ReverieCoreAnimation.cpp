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

#include <kis_image.h>
#include <kis_image_animation_interface.h>
#include <kis_node.h>
#include <kis_paint_layer.h>
#include <kis_paint_device.h>
#include <kis_keyframe_channel.h>
#include <kis_raster_keyframe_channel.h>
#include <kis_time_span.h>
#include <kis_undo_adapter.h>
#include <kundo2command.h>
#include <kundo2magicstring.h>

#include <QSet>
#include <QVector>
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
    return true;
}
