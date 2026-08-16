/*
 * JNI bridge: Kotlin/Compose UI <-> ReverieCore C++ engine
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <QCoreApplication>
#include <QPainter>
#include <QString>
#include <QByteArray>

#include "ReverieCore.h"

#include "reverie_jni_common.h"

// The single core instance (UI is single-window)
static ReverieCore *g_core = nullptr;

// Qt requires a (core) application instance for QObject-based classes such
// as KisImage. On Android without a QtActivity we create a QCoreApplication
// on first use - the engine is software-rendered so no GUI is needed.
static QCoreApplication *g_app = nullptr;

static void ensureQtApp()
{
    if (!g_app) {
        static int argc = 1;
        static char *argv[] = { const_cast<char *>("reverie"), nullptr };
        g_app = new QCoreApplication(argc, argv);
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
    void *qtCore = dlopen("libQt6Core_arm64-v8a.so", RTLD_NOW);
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
    return core()->newDocument(w, h) ? JNI_TRUE : JNI_FALSE;
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
Java_com_reverie_paint_core_ReverieCoreBridge_renderToBuffer(JNIEnv *env, jobject, jobject bitmap, jboolean forceFull)
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
}
