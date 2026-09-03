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

extern "C" {

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
Java_com_reverie_paint_core_ReverieCoreBridge_gradientFill(JNIEnv *, jobject, jint x1, jint y1, jint x2, jint y2, jint type, jint repeat, jboolean reverse)
{
    core()->gradientFill(x1, y1, x2, y2, type, repeat, reverse);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerContent(JNIEnv *, jobject, jint dx, jint dy)
{
    core()->moveLayerContent(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerContentLayers(
    JNIEnv *env, jobject, jintArray layers, jint dx, jint dy)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    core()->moveLayerContentLayers(list, dx, dy);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_contentBounds(JNIEnv *env, jobject)
{
    const QRect b = core()->contentBounds();
    jintArray arr = env->NewIntArray(4);
    if (arr) {
        jint v[4] = {b.x(), b.y(), b.width(), b.height()};
        env->SetIntArrayRegion(arr, 0, 4, v);
    }
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_contentBoundsLayers(JNIEnv *env, jobject, jintArray layers)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    const QRect b = core()->contentBounds(list);
    jintArray arr = env->NewIntArray(4);
    if (arr) {
        jint v[4] = {b.x(), b.y(), b.width(), b.height()};
        env->SetIntArrayRegion(arr, 0, 4, v);
    }
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyTransform(JNIEnv *env, jobject,
                                                             jdouble xscale, jdouble yscale,
                                                             jdouble xshear, jdouble yshear,
                                                             jdouble rotationRad,
                                                             jdouble xtranslate, jdouble ytranslate,
                                                             jdouble originX, jdouble originY)
{
    return core()->applyTransform(xscale, yscale, xshear, yshear,
                                  rotationRad, xtranslate, ytranslate,
                                  originX, originY)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyTransformLayers(
    JNIEnv *env, jobject, jintArray layers,
    jdouble xscale, jdouble yscale, jdouble xshear, jdouble yshear,
    jdouble rotationRad, jdouble xtranslate, jdouble ytranslate,
    jdouble originX, jdouble originY)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    return core()->applyTransformLayers(list, xscale, yscale, xshear, yshear,
                                        rotationRad, xtranslate, ytranslate,
                                        originX, originY)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyPerspectiveTransform(JNIEnv *env, jobject,
                                                                        jdouble x0, jdouble y0,
                                                                        jdouble x1, jdouble y1,
                                                                        jdouble x2, jdouble y2,
                                                                        jdouble x3, jdouble y3,
                                                                        jdouble origX, jdouble origY,
                                                                        jdouble origW, jdouble origH)
{
    return core()->applyPerspectiveTransform(x0, y0, x1, y1, x2, y2, x3, y3,
                                             origX, origY, origW, origH)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyWarpMeshTransform(JNIEnv *env, jobject,
                                                                     jdoubleArray origXs, jdoubleArray origYs,
                                                                     jdoubleArray transfXs, jdoubleArray transfYs,
                                                                     jint count,
                                                                     jdouble origX, jdouble origY,
                                                                     jdouble origW, jdouble origH)
{
    if (count <= 0) return JNI_FALSE;
    jdouble *ox = env->GetDoubleArrayElements(origXs, nullptr);
    jdouble *oy = env->GetDoubleArrayElements(origYs, nullptr);
    jdouble *tx = env->GetDoubleArrayElements(transfXs, nullptr);
    jdouble *ty = env->GetDoubleArrayElements(transfYs, nullptr);

    QVector<QPointF> origPoints;
    QVector<QPointF> transfPoints;
    origPoints.reserve(count);
    transfPoints.reserve(count);
    for (int i = 0; i < count; ++i) {
        origPoints.append(QPointF(ox[i], oy[i]));
        transfPoints.append(QPointF(tx[i], ty[i]));
    }

    env->ReleaseDoubleArrayElements(origXs, ox, JNI_ABORT);
    env->ReleaseDoubleArrayElements(origYs, oy, JNI_ABORT);
    env->ReleaseDoubleArrayElements(transfXs, tx, JNI_ABORT);
    env->ReleaseDoubleArrayElements(transfYs, ty, JNI_ABORT);

    return core()->applyWarpMeshTransform(origPoints, transfPoints, origX, origY, origW, origH)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cropCanvas(JNIEnv *, jobject, jint x, jint y, jint w, jint h)
{
    core()->cropCanvas(x, y, w, h);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_floodFillAt(JNIEnv *, jobject, jint x, jint y, jint tolerance, jboolean sampleMerged)
{
    core()->floodFillAt(x, y, tolerance, sampleMerged);
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_pickColorAt(JNIEnv *env, jobject, jint x, jint y, jboolean currentLayerOnly)
{
    const QString c = core()->pickColorAt(x, y, currentLayerOnly);
    return c.isEmpty() ? nullptr : env->NewStringUTF(c.toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquify(JNIEnv *, jobject, jint fx, jint fy, jint tx, jint ty, jdouble strength, jint mode)
{
    core()->liquify(fx, fy, tx, ty, strength, mode);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyBegin(JNIEnv *env, jobject, jintArray layers)
{
    // Signature must match the Kotlin declaration exactly: JNI resolves by
    // symbol name only and does NOT validate arity - an extra count param
    // read a garbage register and the layer list resolved to nothing
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    core()->liquifyBegin(list);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyEnd(JNIEnv *, jobject)
{
    core()->liquifyEnd();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyCancel(JNIEnv *, jobject)
{
    core()->liquifyCancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLiquifyBrushSize(JNIEnv *, jobject, jdouble size)
{
    core()->setLiquifyBrushSize(size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawText(JNIEnv *env, jobject, jint x, jint y, jstring text, jdouble fontSize)
{
    const char *c = env->GetStringUTFChars(text, nullptr);
    core()->drawText(x, y, QString::fromUtf8(c), fontSize);
    env->ReleaseStringUTFChars(text, c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_stampBitmap(
    JNIEnv *env, jobject, jint x, jint y, jobject bitmap)
{
    if (!bitmap) return;
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        return;
    }
    core()->stampBitmap(x, y, (int)info.width, (int)info.height, pixels);
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setShapeStrokeWidth(JNIEnv *, jobject, jdouble w)
{
    core()->setShapeStrokeWidth(w);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setShapeFilled(JNIEnv *, jobject, jboolean f)
{
    core()->setShapeFilled(f == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawShape(JNIEnv *, jobject, jint kind, jint x1, jint y1, jint x2, jint y2, jboolean filled)
{
    core()->drawShape(kind, x1, y1, x2, y2, filled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_startTransformPreview(JNIEnv *env, jobject, jobject bitmap)
{
    if (!bitmap) return JNI_FALSE;
    QImage outImage;
    bool res = core()->startTransformPreview(QVector<int>(), &outImage);
    if (res && !outImage.isNull()) {
        AndroidBitmapInfo info;
        void *pixels;
        if (AndroidBitmap_getInfo(env, bitmap, &info) >= 0 &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0) {

            if (outImage.width() == (int)info.width && outImage.height() == (int)info.height) {
                memcpy(pixels, outImage.constBits(), size_t(info.width) * info.height * 4);
            } else {
                QImage scaled = outImage.scaled(info.width, info.height, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
                memcpy(pixels, scaled.constBits(), size_t(info.width) * info.height * 4);
            }
            AndroidBitmap_unlockPixels(env, bitmap);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_startTransformPreviewLayers(
    JNIEnv *env, jobject, jintArray layers, jobject bitmap)
{
    if (!bitmap) return JNI_FALSE;
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    QImage outImage;
    bool res = core()->startTransformPreview(list, &outImage);
    if (res && !outImage.isNull()) {
        AndroidBitmapInfo info;
        void *pixels;
        if (AndroidBitmap_getInfo(env, bitmap, &info) >= 0 &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0) {

            if (outImage.width() == (int)info.width && outImage.height() == (int)info.height) {
                memcpy(pixels, outImage.constBits(), size_t(info.width) * info.height * 4);
            } else {
                QImage scaled = outImage.scaled(info.width, info.height, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
                memcpy(pixels, scaled.constBits(), size_t(info.width) * info.height * 4);
            }
            AndroidBitmap_unlockPixels(env, bitmap);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cancelTransformPreview(JNIEnv *env, jobject)
{
    core()->cancelTransformPreview();
}
}
