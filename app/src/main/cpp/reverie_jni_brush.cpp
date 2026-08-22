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
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSecondaryColor(JNIEnv *env, jobject, jstring color)
{
    const char *c = env->GetStringUTFChars(color, nullptr);
    if (c) {
        core()->setBrushSecondaryColor(QColor(QString::fromUtf8(c)));
        env->ReleaseStringUTFChars(color, c);
    }
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
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSmudgeRate(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSmudgeRate(v);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushSmudgeLength(JNIEnv *, jobject, jdouble v)
{
    core()->setBrushSmudgeLength(v);
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
}
