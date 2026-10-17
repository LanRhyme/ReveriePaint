/*
 * JNI bridge: Kotlin/Compose UI <-> ReverieCore C++ engine
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <sched.h>
#include <unistd.h>
#include <stdio.h>
#include <errno.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <QCoreApplication>
#include <QPainter>
#include <QString>
#include <QByteArray>

#include "ReverieCore.h"
#include "ReverieCrashHandler.h"

#include "reverie_jni_common.h"
#include <kis_image_config.h>
#include <filter/kis_filter_registry.h>
#include <generator/kis_generator_registry.h>
#include <KisResourceLoaderRegistry.h>
#include "ReverieCoreInternal.h"

// The single core instance (UI is single-window)
static ReverieCore *g_core = nullptr;

// Qt requires a (core) application instance for QObject-based classes such
// as KisImage. On Android without a QtActivity we create a QCoreApplication
// on first use - the engine is software-rendered so no GUI is needed.
static QCoreApplication *g_app = nullptr;

static void reverieQtMessageHandler(QtMsgType type, const QMessageLogContext &context, const QString &msg)
{
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
    if (f) {
        char line[256];
        long memTotalKb = 0;
        long memAvailKb = 0;
        long memFreeKb = 0;
        long buffersKb = 0;
        long cachedKb = 0;
        while (fgets(line, sizeof(line), f)) {
            if (sscanf(line, "MemTotal: %ld kB", &memTotalKb) == 1) continue;
            if (sscanf(line, "MemAvailable: %ld kB", &memAvailKb) == 1) continue;
            if (sscanf(line, "MemFree: %ld kB", &memFreeKb) == 1) continue;
            if (sscanf(line, "Buffers: %ld kB", &buffersKb) == 1) continue;
            if (sscanf(line, "Cached: %ld kB", &cachedKb) == 1) continue;
        }
        fclose(f);
        if (memTotalKb > 0) totalMb = memTotalKb / 1024;
        if (memAvailKb > 0) {
            availMb = memAvailKb / 1024;
        } else if (memFreeKb > 0) {
            availMb = (memFreeKb + buffersKb + cachedKb) / 1024;
        }
    }
    if (totalMb <= 0) {
        long pages = sysconf(_SC_PHYS_PAGES);
        long pageSize = sysconf(_SC_PAGE_SIZE);
        if (pages > 0 && pageSize > 0) {
            totalMb = (pages * pageSize) / (1024 * 1024);
        }
    }
    if (availMb <= 0) {
        availMb = totalMb > 0 ? totalMb / 3 : 1024;
    }
}

static void tuneKritaTileMemory(KisImageConfig &config)
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

static void ensureQtApp()
{
    if (!g_app) {
        static int argc = 1;
        static char *argv[] = { const_cast<char *>("reverie"), nullptr };
        g_app = new QCoreApplication(argc, argv);
        qInstallMessageHandler(reverieQtMessageHandler);

        try {
            KisImageConfig config(false);
            tuneKritaTileMemory(config);
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
    ensureQtApp();
    if (!g_core) {
        g_core = new ReverieCore();
    }
    return g_core;
}


extern "C" {


// Provide our own JNI_OnLoad. The engine links Qt6Core (for Krita's
// QObject-based image classes) and Qt6Core's Android integration needs its
// JavaVM registered before any QCoreApplication can be constructed.
//
// The crucial step is QtAndroidPrivate::initJNI(vm, env): it stores the
// JavaVM, reads QtNative.activity()/service()/classLoader() into global
// refs, and registers native dispatch methods. If any JNI call inside it
// hits a pending exception it returns JNI_ERR early and the class loader
// is never set - which later makes every QJniObject::loadClass fail with
// a NULL jclass crash. So we must call it with a clean exception state.
//
// The app class loader itself is registered from the Kotlin side
// (ReverieCoreBridge init) via QtNative.setClassLoader BEFORE this library
// is loaded, so QtNative.classLoader() returns a valid loader here.
typedef jint (*QtInitJNIFn)(JavaVM *, JNIEnv *);

static void clearPendingJniExceptions(JNIEnv *env)
{
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    __android_log_print(ANDROID_LOG_INFO, "RP-JNI", "JNI_OnLoad enter");
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "RP-JNI", "GetEnv failed");
        return JNI_ERR;
    }

    // First make sure the QtNative class is visible to the default FindClass
    // (it lives in the app dex; from JNI_OnLoad the current thread's loader
    // is the app class loader, so this works).
    {
        jclass qtNative = env->FindClass("org/qtproject/qt/android/QtNative");
        if (!qtNative) {
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            // Not fatal: initJNI will find it too (or fail cleanly).
        } else {
            env->DeleteLocalRef(qtNative);
        }
    }

    // Register the JavaVM with Qt's Android layer.
    // dlopen without RTLD_NOLOAD: if the dependency chain hasn't loaded
    // libQt6Core yet (unlikely, but possible with lazy binding), this loads
    // it so the dlsym below can find initJNI.
#if defined(__arm__)
    void *qtCore = dlopen("libQt6Core_armeabi-v7a.so", RTLD_NOW);
#elif defined(__aarch64__)
    void *qtCore = dlopen("libQt6Core_arm64-v8a.so", RTLD_NOW);
#elif defined(__i386__)
    void *qtCore = dlopen("libQt6Core_x86.so", RTLD_NOW);
#elif defined(__x86_64__)
    void *qtCore = dlopen("libQt6Core_x86_64.so", RTLD_NOW);
#else
    void *qtCore = dlopen("libQt6Core.so", RTLD_NOW);
#endif
    if (!qtCore) {
        qtCore = dlopen("libQt6Core.so", RTLD_NOW);
    }
    if (!qtCore) {
        __android_log_print(ANDROID_LOG_ERROR, "RP-JNI", "dlopen Qt6Core failed: %s", dlerror());
    }
    if (qtCore) {
        QtInitJNIFn initJNI = reinterpret_cast<QtInitJNIFn>(
            dlsym(qtCore, "_ZN16QtAndroidPrivate7initJNIEP7_JavaVMP7_JNIEnv"));
        if (initJNI) {
            clearPendingJniExceptions(env);
            const jint rc = initJNI(vm, env);
            if (rc != JNI_OK) {
                __android_log_print(ANDROID_LOG_ERROR, "RP-JNI",
                                    "QtAndroidPrivate::initJNI returned %d", rc);
            }
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "RP-JNI",
                                "dlsym initJNI failed");
        }
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_newDocument(JNIEnv *env, jobject, jint w, jint h)
{
    return core()->newDocument(w, h, false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_newDocumentEx(JNIEnv *env, jobject, jint w, jint h, jboolean infiniteCanvas)
{
    return core()->newDocument(w, h, infiniteCanvas == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_closeDocument(JNIEnv *, jobject)
{
    core()->closeDocument();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setInfiniteCanvas(JNIEnv *, jobject, jboolean infinite)
{
    core()->setInfiniteCanvas(infinite == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_isInfiniteCanvas(JNIEnv *, jobject)
{
    return core()->isInfiniteCanvas() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_fillBackground(JNIEnv *env, jobject, jstring color)
{
    const char *c = env->GetStringUTFChars(color, nullptr);
    core()->fillBackground(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(color, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_clearCanvas(JNIEnv *, jobject)
{
    core()->clearCanvas();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setToolMode(JNIEnv *, jobject, jint mode)
{
    core()->setToolMode(mode);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderPendingDirty(JNIEnv *, jobject)
{
    return core()->renderPendingDirty() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderToBuffer(JNIEnv *env, jobject, jobject bitmap, jboolean forceFull, jintArray outDirty)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.width <= 0 || info.height <= 0) {
        return JNI_FALSE;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    const bool ok = core()->renderToBuffer(static_cast<quint8 *>(pixels),
                                           info.width, info.height,
                                           forceFull == JNI_TRUE);
    AndroidBitmap_unlockPixels(env, bitmap);
    // Report the region native actually wrote (render-buffer coords) so the
    // Kotlin buffer rotation can replicate just that region into the other
    // non-displayed bitmaps
    if (outDirty != nullptr && env->GetArrayLength(outDirty) >= 4) {
        const QRect r = ok ? core()->lastWrittenRect() : QRect();
        jint vals[4] = {r.x(), r.y(), r.width(), r.height()};
        env->SetIntArrayRegion(outDirty, 0, 4, vals);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 显示瓦片一帧最多带回 8 块脏区: 与引擎侧 DirtyRectList 容量一致。
static constexpr int kMaxTileDirtyRects = 8;

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_beginRenderTiles(JNIEnv *env, jobject, jintArray outRects)
{
    if (outRects == nullptr) {
        return 0;
    }
    const jsize len = env->GetArrayLength(outRects);
    const int cap = qMin<int>(len / 4, kMaxTileDirtyRects);
    if (cap <= 0) {
        return 0;
    }
    QRect rects[kMaxTileDirtyRects];
    const int n = core()->beginTileFrame(rects, cap);
    if (n > 0) {
        jint vals[kMaxTileDirtyRects * 4];
        for (int i = 0; i < n; ++i) {
            vals[i * 4 + 0] = rects[i].x();
            vals[i * 4 + 1] = rects[i].y();
            vals[i * 4 + 2] = rects[i].width();
            vals[i * 4 + 3] = rects[i].height();
        }
        env->SetIntArrayRegion(outRects, 0, n * 4, vals);
    }
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderTileToBuffer(JNIEnv *env, jobject,
                                                                 jobject bitmap, jint docX, jint docY,
                                                                 jintArray dirtyRects, jintArray outWritten)
{
    if (bitmap == nullptr) {
        return JNI_FALSE;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    // 引擎按紧密排列的 RGBA8888 (行步长 = width * 4) 写瓦片: 格式或步长不符就拒绝,
    // 否则会按错误的行宽写穿位图内存。
    if (info.width == 0 || info.height == 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.stride != info.width * 4) {
        return JNI_FALSE;
    }

    QRect rects[kMaxTileDirtyRects];
    int count = 0;
    if (dirtyRects != nullptr) {
        const jsize len = env->GetArrayLength(dirtyRects);
        const int max = qMin<int>(len / 4, kMaxTileDirtyRects);
        if (max > 0) {
            jint vals[kMaxTileDirtyRects * 4];
            env->GetIntArrayRegion(dirtyRects, 0, max * 4, vals);
            for (int i = 0; i < max; ++i) {
                const QRect r(vals[i * 4], vals[i * 4 + 1], vals[i * 4 + 2], vals[i * 4 + 3]);
                if (!r.isEmpty()) {
                    rects[count++] = r;
                }
            }
        }
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    QRect written;
    const bool ok = core()->renderTileToBuffer(static_cast<quint8 *>(pixels), int(info.width),
                                               int(info.height), docX, docY, rects, count, &written);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (outWritten != nullptr && env->GetArrayLength(outWritten) >= 4) {
        jint vals[4] = {written.x(), written.y(), written.width(), written.height()};
        env->SetIntArrayRegion(outWritten, 0, 4, vals);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_undo(JNIEnv *, jobject)
{
    core()->undo();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_redo(JNIEnv *, jobject)
{
    core()->redo();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setUndoCaptureEnabled(JNIEnv *, jobject, jboolean on)
{
    core()->setUndoCaptureEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_clearUndoHistory(JNIEnv *, jobject)
{
    core()->clearUndoHistory();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setUndoLimit(JNIEnv *, jobject, jint limit)
{
    core()->setUndoLimit(limit);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_beginUndoMacro(JNIEnv *env, jobject, jstring text)
{
    QString qText;
    if (text) {
        const char *chars = env->GetStringUTFChars(text, nullptr);
        qText = QString::fromUtf8(chars);
        env->ReleaseStringUTFChars(text, chars);
    }
    core()->beginUndoMacro(qText);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_endUndoMacro(JNIEnv *, jobject)
{
    core()->endUndoMacro();
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_canUndo(JNIEnv *, jobject)
{
    return core()->canUndo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_canRedo(JNIEnv *, jobject)
{
    return core()->canRedo() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBackgroundColor(JNIEnv *, jobject, jint color, jboolean commit)
{
    core()->setBackgroundColor(static_cast<quint32>(color), commit);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_docWidth(JNIEnv *, jobject)
{
    return core()->docWidth();
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_docHeight(JNIEnv *, jobject)
{
    return core()->docHeight();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_initNativeCrashHandler(JNIEnv *env, jobject, jstring jLogDir, jstring jAppVersion)
{
    const char *logDir = jLogDir ? env->GetStringUTFChars(jLogDir, nullptr) : nullptr;
    const char *appVer = jAppVersion ? env->GetStringUTFChars(jAppVersion, nullptr) : nullptr;
    reverie_crash_handler_init(logDir, appVer);
    if (logDir) env->ReleaseStringUTFChars(jLogDir, logDir);
    if (appVer) env->ReleaseStringUTFChars(jAppVersion, appVer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_addNativeBreadcrumb(JNIEnv *env, jobject, jstring jTag, jstring jMsg)
{
    const char *tag = jTag ? env->GetStringUTFChars(jTag, nullptr) : nullptr;
    const char *msg = jMsg ? env->GetStringUTFChars(jMsg, nullptr) : nullptr;
    reverie_crash_handler_add_breadcrumb(tag, msg);
    if (tag) env->ReleaseStringUTFChars(jTag, tag);
    if (msg) env->ReleaseStringUTFChars(jMsg, msg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_updateNativeCrashState(JNIEnv *env, jobject, jstring jState)
{
    const char *st = jState ? env->GetStringUTFChars(jState, nullptr) : nullptr;
    reverie_crash_handler_update_state(st);
    if (st) env->ReleaseStringUTFChars(jState, st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_configureTileEngine(JNIEnv *env, jobject, jstring jSwapDir)
{
    ensureQtApp();
    const char *swapDir = jSwapDir ? env->GetStringUTFChars(jSwapDir, nullptr) : nullptr;
    try {
        KisImageConfig config(false);
        if (swapDir && swapDir[0] != '\0') {
            config.setSwapDir(QString::fromUtf8(swapDir));
            __android_log_print(ANDROID_LOG_INFO, "RP-JNI", "Krita swap location configured: %s", swapDir);
        }
        tuneKritaTileMemory(config);
    } catch (...) {}
    if (swapDir) env->ReleaseStringUTFChars(jSwapDir, swapDir);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_bindCurrentThreadToPerformanceCores(JNIEnv *, jobject)
{
    long numCores = sysconf(_SC_NPROCESSORS_CONF);
    if (numCores <= 1) {
        return JNI_FALSE;
    }
    if (numCores > CPU_SETSIZE) {
        numCores = CPU_SETSIZE;
    }

    long maxFreqs[CPU_SETSIZE] = {0};
    long highestFreq = 0;

    for (int i = 0; i < numCores; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (f) {
            long freq = 0;
            if (fscanf(f, "%ld", &freq) == 1) {
                maxFreqs[i] = freq;
                if (freq > highestFreq) {
                    highestFreq = freq;
                }
            }
            fclose(f);
        }
    }

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int boundCores = 0;

    if (highestFreq > 0) {
        // Cores with frequency at least 70% of highestFreq are considered performance cores (mid/big/super)
        long threshold = (highestFreq * 70) / 100;
        for (int i = 0; i < numCores; ++i) {
            if (maxFreqs[i] >= threshold) {
                CPU_SET(i, &cpuset);
                boundCores++;
            }
        }
    }

    // Fallback: if sysfs read failed or all cores excluded, bind upper half
    if (boundCores == 0) {
        int start = numCores > 4 ? numCores / 2 : 0;
        for (int i = start; i < numCores; ++i) {
            CPU_SET(i, &cpuset);
            boundCores++;
        }
    }

    if (sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0) {
        __android_log_print(ANDROID_LOG_INFO, "ReverieCore",
                            "bindCurrentThreadToPerformanceCores: bound to %d performance cores (highestFreq=%ld kHz)",
                            boundCores, highestFreq);
        return JNI_TRUE;
    } else {
        __android_log_print(ANDROID_LOG_WARN, "ReverieCore",
                            "bindCurrentThreadToPerformanceCores: sched_setaffinity failed errno=%d", errno);
        return JNI_FALSE;
    }
}
}
