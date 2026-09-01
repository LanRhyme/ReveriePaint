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

static QVector<int> toIntVector(JNIEnv *env, jintArray arr)
{
    QVector<int> res;
    if (!arr) return res;
    const jsize len = env->GetArrayLength(arr);
    if (len <= 0) return res;
    jint *items = env->GetIntArrayElements(arr, nullptr);
    res.reserve(len);
    for (jsize i = 0; i < len; ++i) {
        res.append(items[i]);
    }
    env->ReleaseIntArrayElements(arr, items, JNI_ABORT);
    return res;
}

extern "C" {

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyFilter(JNIEnv *, jobject, jint index, jint filterId)
{
    core()->applyFilter(index, filterId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyFilterMulti(JNIEnv *env, jobject, jintArray indices, jint filterId)
{
    core()->applyFilterMulti(toIntVector(env, indices), filterId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_beginFilterPreview(JNIEnv *, jobject, jint index)
{
    core()->beginFilterPreview(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_beginFilterPreviewMulti(JNIEnv *env, jobject, jintArray indices)
{
    core()->beginFilterPreviewMulti(toIntVector(env, indices));
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyFilterPreview(JNIEnv *, jobject, jint index, jint filterType, jdouble p1, jdouble p2, jdouble p3, jdouble p4)
{
    core()->applyFilterPreview(index, filterType, p1, p2, p3, p4);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyFilterPreviewMulti(JNIEnv *env, jobject, jintArray indices, jint filterType, jdouble p1, jdouble p2, jdouble p3, jdouble p4)
{
    core()->applyFilterPreviewMulti(toIntVector(env, indices), filterType, p1, p2, p3, p4);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyCurvesLUTPreview(JNIEnv *env, jobject, jint index, jbyteArray lutR, jbyteArray lutG, jbyteArray lutB)
{
    if (!lutR || !lutG || !lutB) return;
    jbyte *r = env->GetByteArrayElements(lutR, nullptr);
    jbyte *g = env->GetByteArrayElements(lutG, nullptr);
    jbyte *b = env->GetByteArrayElements(lutB, nullptr);
    core()->applyCurvesLUTPreview(index, reinterpret_cast<const quint8 *>(r), reinterpret_cast<const quint8 *>(g), reinterpret_cast<const quint8 *>(b));
    env->ReleaseByteArrayElements(lutR, r, JNI_ABORT);
    env->ReleaseByteArrayElements(lutG, g, JNI_ABORT);
    env->ReleaseByteArrayElements(lutB, b, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyCurvesLUTPreviewMulti(JNIEnv *env, jobject, jintArray indices, jbyteArray lutR, jbyteArray lutG, jbyteArray lutB)
{
    if (!lutR || !lutG || !lutB) return;
    jbyte *r = env->GetByteArrayElements(lutR, nullptr);
    jbyte *g = env->GetByteArrayElements(lutG, nullptr);
    jbyte *b = env->GetByteArrayElements(lutB, nullptr);
    core()->applyCurvesLUTPreviewMulti(toIntVector(env, indices), reinterpret_cast<const quint8 *>(r), reinterpret_cast<const quint8 *>(g), reinterpret_cast<const quint8 *>(b));
    env->ReleaseByteArrayElements(lutR, r, JNI_ABORT);
    env->ReleaseByteArrayElements(lutG, g, JNI_ABORT);
    env->ReleaseByteArrayElements(lutB, b, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyGradientMapPreview(JNIEnv *env, jobject, jint index, jintArray gradientLut)
{
    if (!gradientLut) return;
    jint *lut = env->GetIntArrayElements(gradientLut, nullptr);
    core()->applyGradientMapPreview(index, reinterpret_cast<const quint32 *>(lut));
    env->ReleaseIntArrayElements(gradientLut, lut, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyGradientMapPreviewMulti(JNIEnv *env, jobject, jintArray indices, jintArray gradientLut)
{
    if (!gradientLut) return;
    jint *lut = env->GetIntArrayElements(gradientLut, nullptr);
    core()->applyGradientMapPreviewMulti(toIntVector(env, indices), reinterpret_cast<const quint32 *>(lut));
    env->ReleaseIntArrayElements(gradientLut, lut, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_commitFilter(JNIEnv *env, jobject, jint index, jstring filterName)
{
    QString fn = QStringLiteral("Filter");
    if (filterName) {
        const char *c = env->GetStringUTFChars(filterName, nullptr);
        fn = QString::fromUtf8(c);
        env->ReleaseStringUTFChars(filterName, c);
    }
    core()->commitFilter(index, fn);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_commitFilterMulti(JNIEnv *env, jobject, jintArray indices, jstring filterName)
{
    QString fn = QStringLiteral("Filter");
    if (filterName) {
        const char *c = env->GetStringUTFChars(filterName, nullptr);
        fn = QString::fromUtf8(c);
        env->ReleaseStringUTFChars(filterName, c);
    }
    core()->commitFilterMulti(toIntVector(env, indices), fn);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cancelFilter(JNIEnv *, jobject, jint index)
{
    core()->cancelFilter(index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cancelFilterMulti(JNIEnv *env, jobject, jintArray indices)
{
    core()->cancelFilterMulti(toIntVector(env, indices));
}
}
