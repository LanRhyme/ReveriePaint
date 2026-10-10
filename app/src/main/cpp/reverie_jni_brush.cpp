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

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadPatternResources(JNIEnv *env, jobject, jstring dirPath)
{
    if (!dirPath) return 0;
    const char *p = env->GetStringUTFChars(dirPath, nullptr);
    const int n = core()->loadPatternResources(QString::fromUtf8(p));
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
    const int count = d.size();
    jdoubleArray arr = env->NewDoubleArray(count);
    env->SetDoubleArrayRegion(arr, 0, count, d.constData());
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

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetPaintOpId(JNIEnv *env, jobject, jint index)
{
    const QString id = core()->brushPresetPaintOpId(index);
    return env->NewStringUTF(id.toUtf8().constData());
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetCompositeOp(JNIEnv *env, jobject, jint index)
{
    const QString op = core()->brushPresetCompositeOp(index);
    return env->NewStringUTF(op.toUtf8().constData());
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_currentBrushPaintOpId(JNIEnv *env, jobject)
{
    const QString id = core()->currentBrushPaintOpId();
    return env->NewStringUTF(id.toUtf8().constData());
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_brushPresetTipFilename(JNIEnv *env, jobject, jint index)
{
    const QString tip = core()->brushPresetTipFilename(index);
    return env->NewStringUTF(tip.toUtf8().constData());
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
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushPressureDynamics(JNIEnv *, jobject, jboolean enabled, jdouble size, jdouble opacity, jdouble flow, jint curveType)
{
    core()->setBrushPressureDynamics(enabled == JNI_TRUE, size, opacity, flow, curveType);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushOptionDynamics(JNIEnv *env, jobject, jstring optionName, jboolean enabled, jstring sensorId, jstring curvePoints, jdouble strength)
{
    const char *optStr = env->GetStringUTFChars(optionName, nullptr);
    const char *sensorStr = env->GetStringUTFChars(sensorId, nullptr);
    const char *curveStr = env->GetStringUTFChars(curvePoints, nullptr);

    core()->setBrushOptionDynamics(QString::fromUtf8(optStr), enabled == JNI_TRUE, QString::fromUtf8(sensorStr), QString::fromUtf8(curveStr), strength);

    env->ReleaseStringUTFChars(curvePoints, curveStr);
    env->ReleaseStringUTFChars(sensorId, sensorStr);
    env->ReleaseStringUTFChars(optionName, optStr);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushFollowDirection(JNIEnv *, jobject, jboolean enabled)
{
    core()->setBrushFollowDirection(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushJitter(JNIEnv *, jobject, jdouble jitterAngle, jdouble jitterSize)
{
    core()->setBrushJitter(jitterAngle, jitterSize);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushMirror(JNIEnv *, jobject, jboolean flipX, jboolean flipY)
{
    core()->setBrushMirror(flipX == JNI_TRUE, flipY == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushAntiAliasing(JNIEnv *, jobject, jint level)
{
    core()->setBrushAntiAliasing(level);
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
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeStartWithSensors(
    JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure,
    jdouble tiltX, jdouble tiltY, jdouble rotation)
{
    core()->touchStrokeStart(x, y, pressure, tiltX, tiltY, rotation);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeStartWithTime(
    JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure, jdouble timeSeconds)
{
    core()->touchStrokeStart(x, y, pressure, 0.0, 0.0, 0.0, timeSeconds);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeMove(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure)
{
    return core()->touchStrokeMove(x, y, pressure) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeMoveWithTime(
    JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure, jdouble timeSeconds)
{
    return core()->touchStrokeMove(x, y, pressure, 0.0, 0.0, 0.0, timeSeconds) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_resetStrokeCounter(JNIEnv *, jobject)
{
    core()->resetStrokeCounter();
}

// Batched stroke transport: drains all samples accumulated by the Kotlin UI
// thread in ONE JNI call. coords layout supports either [x,y,p,tiltX,tiltY,rotation]
// (stride 6) or [x,y,p] (stride 3) for backward compatibility. count is the sample count.
// Returns true when any flush painted new ink, so the caller only schedules a display refresh.
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_touchStrokeMoveBatch(JNIEnv *env, jobject, jfloatArray coords, jint count)
{
    if (!coords || count <= 0) {
        return JNI_FALSE;
    }
    const jsize len = env->GetArrayLength(coords);
    const int stride = (len >= count * 6) ? 6 : 3;
    if (len < count * stride) {
        return JNI_FALSE;
    }
    jfloat *c = env->GetFloatArrayElements(coords, nullptr);
    if (!c) {
        return JNI_FALSE;
    }
    bool painted = false;
    if (stride == 6) {
        for (int i = 0; i < count; ++i) {
            const int idx = i * 6;
            if (core()->touchStrokeMove(c[idx], c[idx + 1], c[idx + 2], c[idx + 3], c[idx + 4], c[idx + 5])) {
                painted = true;
            }
        }
    } else {
        for (int i = 0; i < count; ++i) {
            if (core()->touchStrokeMove(c[i * 3], c[i * 3 + 1], c[i * 3 + 2])) {
                painted = true;
            }
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

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadStart(JNIEnv *, jobject, jint w, jint h)
{
    return core()->scratchpadStart(w, h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadStrokeStart(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure, jdouble tiltX, jdouble tiltY, jdouble rotation)
{
    return core()->scratchpadStrokeStart(x, y, pressure, tiltX, tiltY, rotation) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadStrokeMove(JNIEnv *, jobject, jdouble x, jdouble y, jdouble pressure, jdouble tiltX, jdouble tiltY, jdouble rotation)
{
    return core()->scratchpadStrokeMove(x, y, pressure, tiltX, tiltY, rotation) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadStrokeEnd(JNIEnv *, jobject)
{
    core()->scratchpadStrokeEnd();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadClear(JNIEnv *, jobject)
{
    core()->scratchpadClear();
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadRender(JNIEnv *env, jobject, jobject bitmap)
{
    if (!bitmap) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.width <= 0 || info.height <= 0 || info.stride < (info.width * 4)) {
        return JNI_FALSE;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    bool ok = core()->scratchpadRender(static_cast<quint8 *>(pixels), info.width, info.height, info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_scratchpadEnd(JNIEnv *, jobject)
{
    core()->scratchpadEnd();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setBrushTexture(JNIEnv *env, jobject, jboolean enabled, jdouble scale, jdouble strength, jstring mode, jstring patternName)
{
    QString modeStr;
    if (mode) {
        const char *m = env->GetStringUTFChars(mode, nullptr);
        if (m) {
            modeStr = QString::fromUtf8(m);
            env->ReleaseStringUTFChars(mode, m);
        }
    }
    QString patStr;
    if (patternName) {
        const char *p = env->GetStringUTFChars(patternName, nullptr);
        if (p) {
            patStr = QString::fromUtf8(p);
            env->ReleaseStringUTFChars(patternName, p);
        }
    }
    core()->setBrushTexture(enabled == JNI_TRUE, scale, strength, modeStr, patStr);
}
}


