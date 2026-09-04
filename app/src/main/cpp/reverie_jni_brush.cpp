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

JNIEXPORT jfloat JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPressureFraction(JNIEnv *, jobject, jfloat pressure)
{
    return core()->brushPressureFraction(pressure);
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
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushAirbrush(JNIEnv *, jobject, jboolean enabled, jdouble rate)
{
    core()->setBrushAirbrush(enabled == JNI_TRUE, rate);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_strokeAirbrushTick(JNIEnv *, jobject)
{
    return core()->strokeAirbrushTick() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setPresetIsEraser(JNIEnv *, jobject, jboolean eraser)
{
    core()->setPresetIsEraser(eraser == JNI_TRUE);
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

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeMove(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure)
{
    return core()->touchStrokeMove(x, y, pressure) ? JNI_TRUE : JNI_FALSE;
}

// Batched stroke transport: drains all samples accumulated by the Kotlin UI
// thread in ONE JNI call. coords layout is [x,y,p] triplets; count is the
// number of triplets. Returns true when any flush painted new ink, so the
// caller only schedules a display refresh after real paint work.
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeMoveBatch(JNIEnv *env, jobject, jfloatArray coords, jint count)
{
    if (!coords || count <= 0) {
        return JNI_FALSE;
    }
    const jsize len = env->GetArrayLength(coords);
    if (len < count * 3) {
        return JNI_FALSE;
    }
    jfloat *c = env->GetFloatArrayElements(coords, nullptr);
    if (!c) {
        return JNI_FALSE;
    }
    bool painted = false;
    for (int i = 0; i < count; ++i) {
        if (core()->touchStrokeMove(c[i * 3], c[i * 3 + 1], c[i * 3 + 2])) {
            painted = true;
        }
    }
    // Flush any pending stroke samples remaining at the end of the batch
    // so ink renders up to the latest point without a 1-frame lag.
    if (core()->hasPendingStrokeSamples()) {
        if (core()->flushStrokeBatch()) {
            painted = true;
        }
    }
    env->ReleaseFloatArrayElements(coords, c, JNI_ABORT);
    return painted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushTipAsset(JNIEnv *env, jobject, jstring jAssetName)
{
    if (!jAssetName) return JNI_FALSE;
    const char *chars = env->GetStringUTFChars(jAssetName, nullptr);
    const QString assetName = QString::fromUtf8(chars);
    env->ReleaseStringUTFChars(jAssetName, chars);
    return core()->setBrushTipAsset(assetName) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeKickIdle(JNIEnv *, jobject)
{
    return core()->touchStrokeKickIdle() ? JNI_TRUE : JNI_FALSE;
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
