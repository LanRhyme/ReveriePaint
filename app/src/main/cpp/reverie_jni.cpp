/*
 * JNI bridge: Kotlin/Compose UI <-> ReverieCore C++ engine
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <string.h>
#include <android/bitmap.h>

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

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_newDocument(JNIEnv *env, jobject, jint w, jint h)
{
    return core()->newDocument(w, h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_fillBackground(JNIEnv *env, jobject, jstring color)
{
    const char *c = env->GetStringUTFChars(color, nullptr);
    core()->fillBackground(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(color, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_clearCanvas(JNIEnv *, jobject)
{
    core()->clearCanvas();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_addLayer(JNIEnv *env, jobject, jstring name)
{
    const char *c = env->GetStringUTFChars(name, nullptr);
    core()->addLayer(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(name, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_removeLayer(JNIEnv *, jobject, jint index)
{
    core()->removeLayer(index);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setCurrentLayer(JNIEnv *, jobject, jint index)
{
    core()->setCurrentLayer(index);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_ReverieCoreBridge_layerCount(JNIEnv *, jobject)
{
    return core()->layerCount();
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_ReverieCoreBridge_layerName(JNIEnv *env, jobject, jint index)
{
    return env->NewStringUTF(core()->layerName(index).toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setLayerBlendMode(JNIEnv *env, jobject, jint index, jstring opId)
{
    const char *c = env->GetStringUTFChars(opId, nullptr);
    core()->setLayerBlendMode(index, QString::fromUtf8(c));
    env->ReleaseStringUTFChars(opId, c);
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_ReverieCoreBridge_layerBlendMode(JNIEnv *env, jobject, jint index)
{
    return env->NewStringUTF(core()->layerBlendMode(index).toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setLayerVisible(JNIEnv *, jobject, jint index, jboolean visible)
{
    core()->setLayerVisible(index, visible == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_layerVisible(JNIEnv *, jobject, jint index)
{
    return core()->layerVisible(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_ReverieCoreBridge_currentLayerIndex(JNIEnv *, jobject)
{
    return core()->currentLayerIndex();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setToolMode(JNIEnv *, jobject, jint mode)
{
    core()->setToolMode(mode);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_floodFillAt(JNIEnv *, jobject, jint x, jint y)
{
    core()->floodFillAt(x, y);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setBrushSize(JNIEnv *, jobject, jdouble size)
{
    core()->setBrushSize(size);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setBrushColor(JNIEnv *env, jobject, jstring color)
{
    const char *c = env->GetStringUTFChars(color, nullptr);
    core()->setBrushColorName(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(color, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_setBrushOpacity(JNIEnv *, jobject, jdouble opacity)
{
    core()->setBrushOpacity(opacity);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_touchStrokeStart(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure)
{
    core()->touchStrokeStart(x, y, pressure);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_touchStrokeMove(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure)
{
    core()->touchStrokeMove(x, y, pressure);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_touchStrokeEnd(JNIEnv *, jobject)
{
    core()->touchStrokeEnd();
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_renderToBuffer(JNIEnv *env, jobject, jobject bitmap)
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
Java_com_reverie_paint_ReverieCoreBridge_pickColorAt(JNIEnv *env, jobject, jint x, jint y)
{
    const QString c = core()->pickColorAt(x, y);
    return env->NewStringUTF(c.toUtf8().constData());
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_savePng(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->savePng(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_loadPng(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->loadPng(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_undo(JNIEnv *, jobject)
{
    core()->undo();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_redo(JNIEnv *, jobject)
{
    core()->redo();
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_canUndo(JNIEnv *, jobject)
{
    return core()->canUndo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_ReverieCoreBridge_canRedo(JNIEnv *, jobject)
{
    return core()->canRedo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_liquify(JNIEnv *, jobject, jint fx, jint fy, jint tx, jint ty)
{
    core()->liquify(fx, fy, tx, ty);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_lassoFill(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count)
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
Java_com_reverie_paint_ReverieCoreBridge_lassoClear(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count)
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
Java_com_reverie_paint_ReverieCoreBridge_drawText(JNIEnv *env, jobject, jint x, jint y, jstring text, jdouble fontSize)
{
    const char *c = env->GetStringUTFChars(text, nullptr);
    core()->drawText(x, y, QString::fromUtf8(c), fontSize);
    env->ReleaseStringUTFChars(text, c);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_ReverieCoreBridge_drawShape(JNIEnv *, jobject, jint kind, jint x1, jint y1, jint x2, jint y2)
{
    core()->drawShape(kind, x1, y1, x2, y2);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_ReverieCoreBridge_docWidth(JNIEnv *, jobject)
{
    return core()->docWidth();
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_ReverieCoreBridge_docHeight(JNIEnv *, jobject)
{
    return core()->docHeight();
}

} // extern "C"
