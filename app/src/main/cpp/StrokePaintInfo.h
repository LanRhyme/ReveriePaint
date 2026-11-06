/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * StrokePaintInfo.h - KisPaintInformation 统一构造入口
 *
 * 全部笔画路径 (镜像/点按/段/tail) 共用同一份 9 参构造约定:
 * 位置 / 压力(qBound 0..1) / tiltX / tiltY / rotation / 切向压力 0 / 透视 1 /
 * StrokeTimeContract::toKritaMs(timeSec) / 速度。
 * 时间入参一律是秒, 只在构造时换算成毫秒 (单位契约见 StrokeTimeContract.h)。
 * 随机源非空才挂接, 与既有各构造点的判空行为一致。
 */
#pragma once

#include <brushengine/kis_paint_information.h>
#include <QLineF>
#include <QPointF>
#include <QtGlobal>

#include "StrokeTimeContract.h"

namespace reverie {

class StrokeSpeedSmoother {
public:
    void reset(const QPointF &startPos = QPointF(), double startTimeSec = 0.0, bool hasStart = false) {
        m_count = 0;
        m_head = 0;
        m_meanDtMs = 4.16; // 默认 240Hz (4.16ms) 初值
        m_hasMean = false;
        m_lastSpeedPxPerMs = 0.0;
        m_lastPos = startPos;
        m_lastTimeSec = startTimeSec;
        m_hasLast = hasStart;
    }

    // 在 dist < spacing 门禁之前对每个有效移动事件调用，返回平滑后的速度 (px/ms)
    qreal next(const QPointF &pos, double timeSec) {
        if (!m_hasLast) {
            reset(pos, timeSec, true);
            return 0.0;
        }
        const qreal dist = QLineF(m_lastPos, pos).length();
        if (dist < 1e-3) {
            return m_lastSpeedPxPerMs; // 零距离重复点直接跳过，对齐 kis_speed_smoother.cpp:118
        }

        const double rawDtMs = (timeSec - m_lastTimeSec) * 1000.0;
        m_lastPos = pos;
        m_lastTimeSec = timeSec;

        // 超过 500ms 的长停顿重置历史窗口，防止停笔后再动分母爆炸
        if (rawDtMs > 500.0) {
            m_count = 0;
            m_head = 0;
            m_lastSpeedPxPerMs = 0.0;
            return 0.0;
        }

        // 更新采样间隔的滤波均值 (对应 KisFilteredRollingMean timeDiffsMean)
        if (rawDtMs >= 1.0 && rawDtMs <= 100.0) {
            m_meanDtMs = m_hasMean ? (0.8 * m_meanDtMs + 0.2 * rawDtMs) : rawDtMs;
            m_hasMean = true;
        }

        m_dist[m_head] = dist;
        m_head = (m_head + 1) % kWindowSize;
        if (m_count < kWindowSize) {
            ++m_count;
        }

        qreal totalDist = 0.0;
        qreal totalTimeMs = 0.0;
        int items = 0;
        for (int i = 0; i < m_count; ++i) {
            const int idx = (m_head - 1 - i + kWindowSize) % kWindowSize;
            totalDist += m_dist[idx];
            totalTimeMs += m_meanDtMs; // 对齐 kis_speed_smoother.cpp:152 (totalTime += avgTimeDiff)
            ++items;
            // 对齐 kis_speed_smoother.cpp:153: 至少 3 个样本且累计距离 >= 5px 即截断窗口
            if (items >= kMinSamples && totalDist >= kMinTrackingDistPx) {
                break;
            }
        }

        if (totalTimeMs > 0.0 && totalDist >= kMinTrackingDistPx) {
            m_lastSpeedPxPerMs = totalDist / totalTimeMs;
        }
        return m_lastSpeedPxPerMs;
    }

    qreal lastSpeedPxPerMs() const { return m_lastSpeedPxPerMs; }
    void advanceLastTime(double timeSec) {
        if (m_hasLast && timeSec > m_lastTimeSec) m_lastTimeSec = timeSec;
    }
    qreal meanDtMs() const { return m_meanDtMs; }

private:
    static constexpr int kWindowSize = 128; // 对齐 Krita 长历史回溯能力，防止 240Hz 慢画达不到 5px 门槛而锁定前速
    static constexpr int kMinSamples = 3;
    static constexpr qreal kMinTrackingDistPx = 5.0;

    qreal m_dist[kWindowSize] = {0.0};
    int m_head = 0;
    int m_count = 0;
    qreal m_meanDtMs = 4.16;
    bool m_hasMean = false;
    qreal m_lastSpeedPxPerMs = 0.0;
    QPointF m_lastPos;
    double m_lastTimeSec = 0.0;
    bool m_hasLast = false;
};

struct StrokePaintSample {
    QPointF pos;
    qreal pressure;
    qreal tiltX;
    qreal tiltY;
    qreal rotation;
    qreal timeSec;
};

// 两个样本之间的速度 (px/ms), 与旧公式同源:
// QLineF 求距 + StrokeTimeContract::speedPxPerMs (秒差, dt 下限 1e-4s)。
inline qreal speedBetween(const StrokePaintSample &a, const StrokePaintSample &b)
{
    return StrokeTimeContract::speedPxPerMs(QLineF(a.pos, b.pos).length(), a.timeSec, b.timeSec);
}

inline KisPaintInformation makePaintInfo(const StrokePaintSample &sample, qreal speedPxPerMs,
                                         const KisRandomSourceSP &random,
                                         const KisPerStrokeRandomSourceSP &perStroke)
{
    // 速度归一化: 上游 kis_painting_information_builder.cpp:137 以
    // m_maxAllowedSpeedValue=30 px/ms 把原始速度折算到 0..1
    // (KisDynamicSensorSpeed::value() 直接把 drawingSpeed() 当 0..1 曲线横坐标),
    // 这里对齐同一刻度, 避免 speed 传感器曲线被原始 px/ms 放大 30 倍。
    const qreal normalizedSpeed = qMin<qreal>(1.0, speedPxPerMs / 30.0);
    KisPaintInformation info(sample.pos,
                             qBound<qreal>(0.0, sample.pressure, 1.0),
                             sample.tiltX, sample.tiltY, sample.rotation,
                             0.0, 1.0,
                             StrokeTimeContract::toKritaMs(sample.timeSec),
                             normalizedSpeed);
    if (random) {
        info.setRandomSource(random);
    }
    if (perStroke) {
        info.setPerStrokeRandomSource(perStroke);
    } else {
        static const KisPerStrokeRandomSourceSP s_fallbackPerStroke(new KisPerStrokeRandomSource());
        info.setPerStrokeRandomSource(s_fallbackPerStroke);
    }
    return info;
}

} // namespace reverie
