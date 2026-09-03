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
Java_com_reverie_paint_core_ReverieCoreBridge_selectContiguousAt(
    JNIEnv *, jobject, jint x, jint y, jint tolerance, jboolean sampleMerged,
    jint expand, jint feather, jint closeGap)
{
    core()->selectContiguousAt(x, y, tolerance, sampleMerged, expand, feather, closeGap);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectSimilarAt(JNIEnv *, jobject, jint x, jint y, jint tolerance, jboolean sampleMerged)
{
    core()->selectSimilarAt(x, y, tolerance, sampleMerged);
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

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_magneticLasso(JNIEnv *env, jobject,
                                                            jint fx, jint fy,
                                                            jint tx, jint ty,
                                                            jint radius)
{
    const QVector<QPoint> path = core()->magneticLasso(QPoint(fx, fy), QPoint(tx, ty), radius);
    jintArray arr = env->NewIntArray(jint(path.size() * 2));
    if (!arr) {
        return nullptr;
    }
    std::vector<jint> flat;
    flat.reserve(size_t(path.size()) * 2);
    for (const QPoint &pt : path) {
        flat.push_back(pt.x());
        flat.push_back(pt.y());
    }
    env->SetIntArrayRegion(arr, 0, jint(flat.size()), flat.data());
    return arr;
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

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_previewLassoOverlay(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count, jint vw, jint vh)
{
    jsize n = env->GetArrayLength(xs);
    if (n < 3 || n != env->GetArrayLength(ys)) {
        return nullptr;
    }
    QVector<QPoint> pts;
    pts.reserve(int(n));
    jint *xbuf = env->GetIntArrayElements(xs, nullptr);
    jint *ybuf = env->GetIntArrayElements(ys, nullptr);
    for (int i = 0; i < int(qMin<jsize>(n, count)); ++i) {
        pts.append(QPoint(xbuf[i], ybuf[i]));
    }
    env->ReleaseIntArrayElements(xs, xbuf, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, ybuf, JNI_ABORT);
    const QVector<quint32> px = core()->previewLassoOverlay(pts, vw, vh);
    if (px.isEmpty()) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(px.size());
    if (!arr) {
        return nullptr;
    }
    env->SetIntArrayRegion(arr, 0, px.size(), reinterpret_cast<const jint *>(px.constData()));
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectionOverlayScaled(JNIEnv *env, jobject, jint vw, jint vh)
{
    const QVector<quint32> px = core()->selectionOverlayScaled(vw, vh);
    if (px.isEmpty()) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(px.size());
    if (!arr) {
        return nullptr;
    }
    env->SetIntArrayRegion(arr, 0, px.size(), reinterpret_cast<const jint *>(px.constData()));
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
}
