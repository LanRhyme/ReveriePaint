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

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerOpacityDirect(JNIEnv *, jobject, jint index, jdouble opacity)
{
    core()->setLayerOpacityDirect(index, opacity);
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
Java_com_reverie_paint_core_ReverieCoreBridge_layerNodeType(JNIEnv *, jobject, jint index)
{
    return core()->layerNodeType(index);
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

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_flipCanvasHorizontal(JNIEnv *, jobject)
{
    core()->flipCanvasHorizontal();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_flipCanvasVertical(JNIEnv *, jobject)
{
    core()->flipCanvasVertical();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_fillLayer(JNIEnv *, jobject, jint index)
{
    core()->fillLayer(index);
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
    return core()->moveLayerAbove(fromIndex, aboveIndex) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerToGroup(JNIEnv *, jobject, jint from, jint group)
{
    return core()->moveLayerToGroup(from, group);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerRelative(JNIEnv *, jobject, jint from, jint target, jboolean placeAbove)
{
    return core()->moveLayerRelative(from, target, placeAbove == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL Java_com_reverie_paint_core_ReverieCoreBridge_mergeDown(JNIEnv *, jobject, jint index)
{
    return core()->mergeDown(index);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_stampVisibleLayers(JNIEnv *, jobject)
{
    return core()->stampVisibleLayers();
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_soloActive(JNIEnv *, jobject)
{
    return core()->soloActive();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerSoloKeep(JNIEnv *env, jobject)
{
    const QVector<int> idx = core()->soloKeepIndices();
    jintArray arr = env->NewIntArray(idx.size());
    if (arr && !idx.isEmpty()) {
        env->SetIntArrayRegion(arr, 0, idx.size(), reinterpret_cast<const jint *>(idx.constData()));
    }
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerSoloRawMode(JNIEnv *, jobject)
{
    return core()->soloRawMode();
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_toggleLayerSoloRawMode(JNIEnv *, jobject)
{
    core()->toggleSoloRawMode();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerUp(JNIEnv *, jobject, jint index)
{
    return core()->moveLayerUp(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerDown(JNIEnv *, jobject, jint index)
{
    return core()->moveLayerDown(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerOut(JNIEnv *, jobject, jint index)
{
    return core()->moveLayerOut(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_addMaskToLayer(JNIEnv *, jobject, jint layerIndex, jint maskType)
{
    return core()->addMaskToLayer(layerIndex, maskType);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_removeMask(JNIEnv *, jobject, jint layerIndex)
{
    return core()->removeMask(layerIndex);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_rasterizeLayer(JNIEnv *, jobject, jint index)
{
    return core()->rasterizeLayer(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_flattenGroup(JNIEnv *, jobject, jint index)
{
    return core()->flattenGroup(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setGroupPassThrough(JNIEnv *, jobject, jint index, jboolean passThrough)
{
    return core()->setGroupPassThrough(index, passThrough == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_groupPassThrough(JNIEnv *, jobject, jint index)
{
    return core()->groupPassThrough(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_addLayerWithType(JNIEnv *env, jobject, jstring name, jint type, jint fillColor)
{
    QString n;
    if (name) {
        const char *c = env->GetStringUTFChars(name, nullptr);
        n = QString::fromUtf8(c);
        env->ReleaseStringUTFChars(name, c);
    }
    return core()->addLayerWithType(n, type, (quint32)fillColor);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_selectionFromLayer(JNIEnv *, jobject, jint index)
{
    return core()->selectionFromLayer(index);
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_createAdjustmentLayer(
    JNIEnv *env, jobject, jstring name, jint filterType,
    jdouble p1, jdouble p2, jdouble p3, jdouble p4)
{
    if (!name) {
        return JNI_FALSE;
    }
    const char *c = env->GetStringUTFChars(name, nullptr);
    const bool ok = core()->createAdjustmentLayer(
        QString::fromUtf8(c), filterType, p1, p2, p3, p4);
    env->ReleaseStringUTFChars(name, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setAdjustmentLayerConfig(
    JNIEnv *env, jobject, jint index, jint filterType,
    jdouble p1, jdouble p2, jdouble p3, jdouble p4, jbyteArray lut)
{
    QByteArray lutBytes;
    if (lut) {
        const jsize len = env->GetArrayLength(lut);
        jbyte *bytes = env->GetByteArrayElements(lut, nullptr);
        lutBytes = QByteArray(reinterpret_cast<const char *>(bytes), len);
        env->ReleaseByteArrayElements(lut, bytes, JNI_ABORT);
    }
    const bool ok = core()->setAdjustmentLayerConfig(
        index, filterType, p1, p2, p3, p4, lutBytes);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_getAdjustmentLayerConfig(
    JNIEnv *env, jobject, jint index)
{
    const QString json = core()->getAdjustmentLayerConfig(index);
    return env->NewStringUTF(json.toUtf8().constData());
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setFillLayerColor(JNIEnv *env, jobject, jint index, jint colorArgb)
{
    return core()->setFillLayerColor(index, static_cast<quint32>(colorArgb)) ? JNI_TRUE : JNI_FALSE;
}
