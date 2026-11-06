/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef REVERIE_PERF_LOG_H
#define REVERIE_PERF_LOG_H

#include <android/log.h>
#include <cstdio>
#include <QtGlobal>
#include <QElapsedTimer>
#include <sys/system_properties.h>

/**
 * 引擎侧按阶段计时日志 (与 Kotlin 侧 PerfTrace / PerfHud 互补)。
 *
 * 开关: `setprop debug.reverie.perf 1` (需要 adb shell; 读属性不需要额外权限,
 * 所以 release 包也能开)。**进程内只读一次**, 改属性后要重启应用才生效。
 *
 * 用途: 定位"大画幅 / 多图层工程上某个文档操作到底卡在哪一段" —— 图层显隐、
 * 撤销、打开工程、缩略图刷新这几条链路的耗时都会被拆成阶段打进 logcat,
 * tag 固定为 `RP-PERF`, 便于一次性 pull 出来对齐 (同一线程的时间戳可比)。
 *
 * 日志格式 (一行一个阶段, 用 key=value 方便 grep/awk):
 *   RP-PERF op=toggleVisible idx=3 visible=0 cmd=1ms graph=12ms waitDone=230ms total=250ms
 */
namespace reverie {

inline bool perfLogEnabled()
{
    static const bool enabled = [] {
        char value[PROP_VALUE_MAX] = {0};
        const int len = __system_property_get("debug.reverie.perf", value);
        if (len <= 0 || !value[0]) {
            return false;
        }
        return value[0] != '0';
    }();
    return enabled;
}

/**
 * 轻量阶段计时器: 构造开始计时, phase() 打一个"距上次"的增量, 析构打 total。
 * 关闭时 phase()/析构都直接返回, 除一次静态 bool 判断外无开销。
 */
class PerfTimer
{
public:
    explicit PerfTimer(const char *op)
        : m_op(op)
    {
        if (perfLogEnabled()) {
            m_timer.start();
        }
    }

    ~PerfTimer()
    {
        if (!perfLogEnabled()) {
            return;
        }
        __android_log_print(ANDROID_LOG_INFO, "RP-PERF", "op=%s total=%lldms%s",
                            m_op, static_cast<long long>(m_timer.elapsed()), m_extra);
    }

    /** 打出一个阶段耗时 (距上一次 phase / 构造) 并记录当前总耗时。 */
    void phase(const char *name)
    {
        if (!perfLogEnabled()) {
            return;
        }
        const qint64 now = m_timer.elapsed();
        __android_log_print(ANDROID_LOG_INFO, "RP-PERF", "op=%s %s=%lldms",
                            m_op, name, static_cast<long long>(now - m_lastMark));
        m_lastMark = now;
    }

    /** 向 total 行追加一个标量 (例如单元数 / 缓存命中数)。 */
    void note(const char *key, qint64 value)
    {
        if (!perfLogEnabled()) {
            return;
        }
        const int used = int(m_extraLen);
        if (used < int(sizeof(m_extra)) - 1) {
            m_extraLen += snprintf(m_extra + used, sizeof(m_extra) - size_t(used),
                                   " %s=%lld", key, static_cast<long long>(value));
        }
    }

private:
    const char *m_op;
    QElapsedTimer m_timer;
    qint64 m_lastMark = 0;
    char m_extra[160] = {0};
    int m_extraLen = 0;
};

} // namespace reverie

#endif // REVERIE_PERF_LOG_H
