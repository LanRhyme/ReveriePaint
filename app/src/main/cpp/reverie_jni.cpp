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
#include <QString>
#include <QByteArray>

#include "ReverieCore.h"

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

static ReverieCore *core()
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
Java_com_reverie_paint_core_ReverieCoreBridge_addLayer(JNIEnv *env, jobject, jstring name)
{
    const char *c = env->GetStringUTFChars(name, nullptr);
    core()->addLayer(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(name, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_removeLayer(JNIEnv *, jobject, jint index)
{
    core()->removeLayer(index);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setCurrentLayer(JNIEnv *, jobject, jint index)
{
    core()->setCurrentLayer(index);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerCount(JNIEnv *, jobject)
{
    return core()->layerCount();
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerName(JNIEnv *env, jobject, jint index)
{
    return env->NewStringUTF(core()->layerName(index).toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerBlendMode(JNIEnv *env, jobject, jint index, jstring opId)
{
    const char *c = env->GetStringUTFChars(opId, nullptr);
    core()->setLayerBlendMode(index, QString::fromUtf8(c));
    env->ReleaseStringUTFChars(opId, c);
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerBlendMode(JNIEnv *env, jobject, jint index)
{
    return env->NewStringUTF(core()->layerBlendMode(index).toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerVisible(JNIEnv *, jobject, jint index, jboolean visible)
{
    core()->setLayerVisible(index, visible == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerVisible(JNIEnv *, jobject, jint index)
{
    return core()->layerVisible(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_currentLayerIndex(JNIEnv *, jobject)
{
    return core()->currentLayerIndex();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSecondaryColor(JNIEnv *env, jobject, jstring color)
{
    const char *c = env->GetStringUTFChars(color, nullptr);
    if (c) {
        core()->setBrushSecondaryColor(QColor(QString::fromUtf8(c)));
        env->ReleaseStringUTFChars(color, c);
    }
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawPolygon(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count, jboolean closed)
{
    if (count < 2) return;
    jint *px = env->GetIntArrayElements(xs, nullptr);
    jint *py = env->GetIntArrayElements(ys, nullptr);
    QVector<QPoint> pts;
    for (int i = 0; i < count; ++i) pts.append(QPoint(px[i], py[i]));
    env->ReleaseIntArrayElements(xs, px, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, py, JNI_ABORT);
    core()->drawPolygon(pts, closed);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_gradientFill(JNIEnv *, jobject, jint x1, jint y1, jint x2, jint y2)
{
    core()->gradientFill(x1, y1, x2, y2);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectShape(JNIEnv *, jobject, jint kind, jint x1, jint y1, jint x2, jint y2)
{
    core()->selectShape(kind, x1, y1, x2, y2);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectPolygon(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count)
{
    if (count < 3) return;
    jint *px = env->GetIntArrayElements(xs, nullptr);
    jint *py = env->GetIntArrayElements(ys, nullptr);
    QVector<QPoint> pts;
    for (int i = 0; i < count; ++i) pts.append(QPoint(px[i], py[i]));
    env->ReleaseIntArrayElements(xs, px, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, py, JNI_ABORT);
    core()->selectPolygon(pts);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectContiguousAt(JNIEnv *, jobject, jint x, jint y)
{
    core()->selectContiguousAt(x, y);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectSimilarAt(JNIEnv *, jobject, jint x, jint y)
{
    core()->selectSimilarAt(x, y);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerContent(JNIEnv *, jobject, jint dx, jint dy)
{
    core()->moveLayerContent(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cropCanvas(JNIEnv *, jobject, jint x, jint y, jint w, jint h)
{
    core()->cropCanvas(x, y, w, h);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setToolMode(JNIEnv *, jobject, jint mode)
{
    core()->setToolMode(mode);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_floodFillAt(JNIEnv *, jobject, jint x, jint y)
{
    core()->floodFillAt(x, y);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSize(JNIEnv *, jobject, jdouble size)
{
    core()->setBrushSize(size);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadBrushPresetsFromDir(JNIEnv *env, jobject, jstring dirPath)
{
    const char *p = env->GetStringUTFChars(dirPath, nullptr);
    const int n = core()->loadBrushPresetsFromDir(QString::fromUtf8(p));
    env->ReleaseStringUTFChars(dirPath, p);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadBrushResources(JNIEnv *env, jobject, jstring dirPath)
{
    const char *p = env->GetStringUTFChars(dirPath, nullptr);
    const int n = core()->loadBrushResources(QString::fromUtf8(p));
    env->ReleaseStringUTFChars(dirPath, p);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadBrushPreset(JNIEnv *, jobject, jint index)
{
    return core()->loadBrushPreset(index);
}

JNIEXPORT jdoubleArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetDefaults(JNIEnv *env, jobject, jint index)
{
    const QVector<double> d = core()->brushPresetDefaults(index);
    jdoubleArray arr = env->NewDoubleArray(3);
    const jdouble tmp[3] = {d.value(0, 20.0), d.value(1, 1.0), d.value(2, 1.0)};
    env->SetDoubleArrayRegion(arr, 0, 3, tmp);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetCount(JNIEnv *, jobject)
{
    return core()->brushPresetCount();
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetName(JNIEnv *env, jobject, jint index)
{
    const QString name = core()->brushPresetName(index);
    return env->NewStringUTF(name.toUtf8().constData());
}

JNIEXPORT jbyteArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetThumbData(JNIEnv *env, jobject, jint index)
{
    const QByteArray data = core()->brushPresetThumbData(index);
    jbyteArray arr = env->NewByteArray(data.size());
    if (data.size() > 0) {
        env->SetByteArrayRegion(arr, 0, data.size(),
                                reinterpret_cast<const jbyte *>(data.constData()));
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_currentBrushPreset(JNIEnv *, jobject)
{
    return core()->currentBrushPreset();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushFlow(JNIEnv *, jobject, jdouble flow)
{
    core()->setBrushFlow(flow);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSpacing(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSpacing(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushAngle(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushAngle(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushScatter(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushScatter(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushFade(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushFade(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSoftness(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSoftness(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushRatio(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushRatio(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSharpness(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSharpness(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushRotation(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushRotation(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushCompositeOp(JNIEnv *env, jobject, jstring op)
{
    const char *o = env->GetStringUTFChars(op, nullptr);
    core()->setBrushCompositeOp(QString::fromUtf8(o));
    env->ReleaseStringUTFChars(op, o);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushColor(JNIEnv *env, jobject, jstring color)
{
    const char *c = env->GetStringUTFChars(color, nullptr);
    core()->setBrushColorName(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(color, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushOpacity(JNIEnv *, jobject, jdouble opacity)
{
    core()->setBrushOpacity(opacity);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeStart(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure)
{
    core()->touchStrokeStart(x, y, pressure);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeMove(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure)
{
    core()->touchStrokeMove(x, y, pressure);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeEnd(JNIEnv *, jobject)
{
    core()->touchStrokeEnd();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeCancel(JNIEnv *, jobject)
{
    core()->touchStrokeCancel();
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderToBuffer(JNIEnv *env, jobject, jobject bitmap)
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
                                           info.width, info.height);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_pickColorAt(JNIEnv *env, jobject, jint x, jint y)
{
    const QString c = core()->pickColorAt(x, y);
    return env->NewStringUTF(c.toUtf8().constData());
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_savePng(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->savePng(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadPng(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->loadPng(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
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

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquify(JNIEnv *, jobject, jint fx, jint fy, jint tx, jint ty)
{
    core()->liquify(fx, fy, tx, ty);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_lassoSelect(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count)
{
    if (count < 3) return;
    jint *px = env->GetIntArrayElements(xs, nullptr);
    jint *py = env->GetIntArrayElements(ys, nullptr);
    QVector<QPoint> pts;
    for (int i = 0; i < count; ++i) pts.append(QPoint(px[i], py[i]));
    env->ReleaseIntArrayElements(xs, px, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, py, JNI_ABORT);
    core()->lassoSelect(pts);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_lassoFill(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count)
{
    jint *px = env->GetIntArrayElements(xs, nullptr);
    jint *py = env->GetIntArrayElements(ys, nullptr);
    QVector<QPoint> pts;
    for (int i = 0; i < count; ++i) {
        pts.append(QPoint(px[i], py[i]));
    }
    env->ReleaseIntArrayElements(xs, px, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, py, JNI_ABORT);
    core()->lassoFill(pts);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_lassoClear(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count)
{
    jint *px = env->GetIntArrayElements(xs, nullptr);
    jint *py = env->GetIntArrayElements(ys, nullptr);
    QVector<QPoint> pts;
    for (int i = 0; i < count; ++i) {
        pts.append(QPoint(px[i], py[i]));
    }
    env->ReleaseIntArrayElements(xs, px, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, py, JNI_ABORT);
    core()->lassoClear(pts);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawText(JNIEnv *env, jobject, jint x, jint y, jstring text, jdouble fontSize)
{
    const char *c = env->GetStringUTFChars(text, nullptr);
    core()->drawText(x, y, QString::fromUtf8(c), fontSize);
    env->ReleaseStringUTFChars(text, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawShape(JNIEnv *, jobject, jint kind, jint x1, jint y1, jint x2, jint y2)
{
    core()->drawShape(kind, x1, y1, x2, y2);
}

// ---- Full layer system ----
extern "C" JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_addGroupLayer(JNIEnv *env, jobject, jstring name)
{
    const char *c = env->GetStringUTFChars(name, nullptr);
    const jint r = core()->addGroupLayer(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(name, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_copyLayer(JNIEnv *, jobject, jint index)
{
    return core()->copyLayer(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_clearLayer(JNIEnv *, jobject, jint index)
{
    core()->clearLayer(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerName(JNIEnv *env, jobject, jint index, jstring name)
{
    const char *c = env->GetStringUTFChars(name, nullptr);
    core()->setLayerName(index, QString::fromUtf8(c));
    env->ReleaseStringUTFChars(name, c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerOpacity(JNIEnv *, jobject, jint index, jdouble opacity)
{
    core()->setLayerOpacity(index, opacity);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerOpacity(JNIEnv *, jobject, jint index)
{
    return core()->layerOpacity(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerLocked(JNIEnv *, jobject, jint index, jboolean locked)
{
    core()->setLayerLocked(index, locked);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerLocked(JNIEnv *, jobject, jint index)
{
    return core()->layerLocked(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerAlphaLocked(JNIEnv *, jobject, jint index, jboolean locked)
{
    core()->setLayerAlphaLocked(index, locked);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerAlphaLocked(JNIEnv *, jobject, jint index)
{
    return core()->layerAlphaLocked(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerColorLabel(JNIEnv *, jobject, jint index, jint label)
{
    core()->setLayerColorLabel(index, label);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerColorLabel(JNIEnv *, jobject, jint index)
{
    return core()->layerColorLabel(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerIsGroup(JNIEnv *, jobject, jint index)
{
    return core()->layerIsGroup(index);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerDepth(JNIEnv *, jobject, jint index)
{
    return core()->layerDepth(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerBackground(JNIEnv *, jobject, jint index)
{
    return core()->layerBackground(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerClipped(JNIEnv *, jobject, jint index)
{
    return core()->layerClipped(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerClipped(JNIEnv *, jobject, jint index, jboolean clipped)
{
    core()->setLayerClipped(index, clipped);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_flipLayerHorizontal(JNIEnv *, jobject, jint index)
{
    core()->flipLayerHorizontal(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_flipLayerVertical(JNIEnv *, jobject, jint index)
{
    core()->flipLayerVertical(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayer(JNIEnv *, jobject, jint from, jint to)
{
    return core()->moveLayer(from, to);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerAbove(
    JNIEnv *env, jobject, jint fromIndex, jint aboveIndex)
{
    return g_core->moveLayerAbove(fromIndex, aboveIndex) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerToGroup(JNIEnv *, jobject, jint from, jint group)
{
    return core()->moveLayerToGroup(from, group);
}

JNIEXPORT jboolean JNICALL Java_com_reverie_paint_core_ReverieCoreBridge_mergeDown(JNIEnv *, jobject, jint index)
{
    return core()->mergeDown(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_soloLayer(JNIEnv *, jobject, jint index)
{
    core()->soloLayer(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerSoloed(JNIEnv *, jobject, jint index)
{
    return core()->layerSoloed(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyFilter(JNIEnv *, jobject, jint index, jint filterId)
{
    core()->applyFilter(index, filterId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectionFromLayer(JNIEnv *, jobject, jint index)
{
    return core()->selectionFromLayer(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_hasSelection(JNIEnv *, jobject)
{
    return core()->hasSelection();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectionMask(JNIEnv *env, jobject)
{
    const QByteArray mask = core()->selectionMask();
    jbyteArray arr = env->NewByteArray(mask.size());
    if (mask.size() > 0) {
        env->SetByteArrayRegion(arr, 0, mask.size(),
                                reinterpret_cast<const jbyte *>(mask.constData()));
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectAll(JNIEnv *, jobject)
{
    core()->selectAll();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_invertSelection(JNIEnv *, jobject)
{
    core()->invertSelection();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setSelectionMode(JNIEnv *, jobject, jint mode)
{
    core()->setSelectionMode(mode);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectionMode(JNIEnv *, jobject)
{
    return core()->selectionMode();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_featherSelection(JNIEnv *, jobject, jint radius)
{
    core()->featherSelection(radius);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_expandSelection(JNIEnv *, jobject, jint px)
{
    core()->expandSelection(px);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_contractSelection(JNIEnv *, jobject, jint px)
{
    core()->contractSelection(px);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_smoothSelection(JNIEnv *, jobject, jint radius)
{
    core()->smoothSelection(radius);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_clearSelection(JNIEnv *, jobject)
{
    core()->clearSelection();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderLayerThumb(JNIEnv *env, jobject, jint index, jobject bitmap)
{
    if (!bitmap) {
        return JNI_FALSE;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    const bool ok =
        core()->renderLayerThumb(index, info.width, info.height, pixels, info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
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

} // extern "C"
