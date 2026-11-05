/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * StrokeTimeContract.h - 笔画时间单位边界
 *
 * StrokeSample::time、JNI timeSeconds、录制文件都是秒。
 * KisPaintInformation::currentTime 是毫秒
 * （kis_paint_information.h: "Number of ms since the beginning of the stroke"）。
 * 只在构造 KisPaintInformation 时换算，存储单位不改。
 */
#pragma once

namespace reverie {

class StrokeTimeContract {
public:
    static double toKritaMs(double seconds)
    {
        return seconds > 0.0 ? seconds * 1000.0 : 0.0;
    }

    // 与旧公式 dist / (max(1e-4, t1-t0) * 1000) 同一运算顺序。dt 下限 1e-4 秒。
    // 不经过 toKritaMs：那条会把非正时间夹成 0，速度分母就和旧公式不一致。
    static double speedPxPerMs(double dist, double t0Sec, double t1Sec)
    {
        const double dtSec = t1Sec - t0Sec;
        // 物理采样周期下限设为 3ms (对应 ~330Hz 采样上限), 彻底消除同毫秒点或微秒抖动除零导致的速度暴走尖峰 (竹节根因)
        const double effDt = dtSec > 0.003 ? dtSec : 0.003;
        return dist / (effDt * 1000.0);
    }
};

} // namespace reverie
