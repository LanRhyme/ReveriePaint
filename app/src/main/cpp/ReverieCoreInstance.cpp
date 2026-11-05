/*
 * ReverieCore 进程级单例与 Qt 初始化 (从 reverie_jni_core.cpp 迁出, 供 libreverie_core 导出)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么单独成文件: 拆库之后 libreverie_jni 不再链接 Krita, 引擎的创建与 Qt 初始化必须
 * 留在核心库里; JNI 层只保留 JNI_OnLoad 与 Java_ 函数, 通过 NEEDED 依赖调用这里的
 * core() / reverieEnsureQtApp() / reverieTuneTileMemory()。
 */

#include "ReverieCoreInstance.h"

#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <sys/system_properties.h>

#include <atomic>
#include <chrono>

#include <QCoreApplication>
#include <QString>

#include "ReverieCore.h"
#include "ReverieCoreInternal.h"

#include <kis_image_config.h>
#include <filter/kis_filter_registry.h>
#include <generator/kis_generator_registry.h>
#include <KisResourceLoaderRegistry.h>

// The single core instance (UI is single-window)
static ReverieCore *g_core = nullptr;

// Qt requires a (core) application instance for QObject-based classes such
// as KisImage. On Android without a QtActivity we create a QCoreApplication
// on first use - the engine is software-rendered so no GUI is needed.
static QCoreApplication *g_app = nullptr;

// Qt 日志门禁: 真机上 Krita/Qt 的 Warning 狂喷 (每条 toUtf8 堆分配 + log_print 同步 IPC)
// 会拖垮渲染线程, 必须在任何字符串转换/分配之前过滤。
// - Fatal/Critical: 无条件放行 (崩溃诊断不能丢)
// - Warning: 令牌桶限流, 每秒重置 25 条预算, 超预算丢弃
// - Debug/Info: 仅当系统属性 debug.reverie.qtlog == "1" 时放行 (安装 handler 时一次性读取)
static std::atomic<bool> g_qtLogVerbose{false};
static std::atomic<qint64> g_qtWarnWindowSec{0};
static std::atomic<int> g_qtWarnBudget{0};
static const int kQtWarnBudgetPerSec = 25;

static void reverieQtMessageHandler(QtMsgType type, const QMessageLogContext &context, const QString &msg)
{
    if (type != QtFatalMsg && type != QtCriticalMsg) {
        if (type == QtWarningMsg) {
            const qint64 nowSec = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            qint64 windowSec = g_qtWarnWindowSec.load(std::memory_order_relaxed);
            if (nowSec != windowSec &&
                g_qtWarnWindowSec.compare_exchange_strong(windowSec, nowSec, std::memory_order_relaxed)) {
                g_qtWarnBudget.store(kQtWarnBudgetPerSec, std::memory_order_relaxed);
            }
            int budget = g_qtWarnBudget.load(std::memory_order_relaxed);
            bool admitted = false;
            while (budget > 0) {
                if (g_qtWarnBudget.compare_exchange_weak(budget, budget - 1, std::memory_order_relaxed)) {
                    admitted = true;
                    break;
                }
            }
            if (!admitted) {
                return; // 超预算丢弃
            }
        } else if (!g_qtLogVerbose.load(std::memory_order_relaxed)) {
            return; // QtDebugMsg / QtInfoMsg: verbose 未开启, 丢弃
        }
    }

    int priority = ANDROID_LOG_DEBUG;
    switch (type) {
    case QtDebugMsg: priority = ANDROID_LOG_DEBUG; break;
    case QtInfoMsg: priority = ANDROID_LOG_INFO; break;
    case QtWarningMsg: priority = ANDROID_LOG_WARN; break;
    case QtCriticalMsg: priority = ANDROID_LOG_ERROR; break;
    case QtFatalMsg: priority = ANDROID_LOG_FATAL; break;
    }
    __android_log_print(priority, "ReverieQt", "[%s:%u] %s",
                        context.file ? context.file : "unknown",
                        context.line,
                        msg.toUtf8().constData());
}

static void querySystemMemoryMb(long &totalMb, long &availMb)
{
    totalMb = 0;
    availMb = 0;
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemTotal: %ld kB", &totalMb) == 1) {
            totalMb /= 1024;
        } else if (sscanf(line, "MemAvailable: %ld kB", &availMb) == 1) {
            availMb /= 1024;
        }
    }
    fclose(f);
    if (availMb <= 0) {
        availMb = totalMb > 0 ? totalMb / 3 : 1024;
    }
}

void reverieTuneTileMemory(KisImageConfig &config)
{
    long totalMb = 0;
    long availMb = 0;
    querySystemMemoryMb(totalMb, availMb);

    // 目标硬上限: 绝对值锁死在 [384MB, 1400MB] 之间，且绝不超过当前可用内存的 50%
    const double targetHardMb = qBound(384.0, double(availMb) * 0.50, 1400.0);
    const double targetSoftMb = qBound(256.0, targetHardMb * 0.70, 1000.0);

    const double hardPercent = (totalMb > 0) ? qBound(5.0, (targetHardMb / double(totalMb)) * 100.0, 45.0) : 25.0;
    const double softPercent = (totalMb > 0) ? qBound(3.0, (targetSoftMb / double(totalMb)) * 100.0, hardPercent * 0.75) : 15.0;
    const double poolPercent = qBound(2.0, softPercent * 0.5, 10.0);

    config.setMemorySoftLimitPercent(softPercent);
    config.setMemoryHardLimitPercent(hardPercent);
    config.setMemoryPoolLimitPercent(poolPercent);
    config.setMaxSwapSize(8192);

    __android_log_print(ANDROID_LOG_INFO, "RP-JNI",
        "Krita tile memory auto-tuned: total=%ldMB, avail=%ldMB, soft=%.1fMB (%.1f%%), hard=%.1fMB (%.1f%%), pool=%.1f%%",
        totalMb, availMb, targetSoftMb, softPercent, targetHardMb, hardPercent, poolPercent);
}

void reverieEnsureQtApp()
{
    if (!g_app) {
        static int argc = 1;
        static char *argv[] = { const_cast<char *>("reverie"), nullptr };
        g_app = new QCoreApplication(argc, argv);
        char qtLogProp[PROP_VALUE_MAX] = {0};
        if (__system_property_get("debug.reverie.qtlog", qtLogProp) > 0 &&
            strcmp(qtLogProp, "1") == 0) {
            g_qtLogVerbose.store(true);
        }
        qInstallMessageHandler(reverieQtMessageHandler);

        try {
            KisImageConfig config(false);
            reverieTuneTileMemory(config);
        } catch (...) {}

        // Krita's KisFilterRegistry, KisGeneratorRegistry, and KisResourceLoaderRegistry
        // use qApp->findChild<RegistryType>() and if missing, create new RegistryType(qApp).
        // If called from a secondary thread, Qt refuses to attach them as children of qApp
        // ("Cannot create children for a parent that is in a different thread"), causing
        // findChild to fail on every future call and creating brand new empty registries.
        // We must initialize them on qApp's creation thread so they cleanly attach to qApp.
        KisFilterRegistry::instance();
        KisGeneratorRegistry::instance();
        KisResourceLoaderRegistry::instance();
        registerCoreFilters();
    }
}

ReverieCore *core()
{
    reverieEnsureQtApp();
    if (!g_core) {
        g_core = new ReverieCore();
    }
    return g_core;
}
