/*
 * JNI bridge: Kotlin/Compose UI <-> ReverieCore C++ engine
 * Animation domain (frames / tracks / keyframes)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>

#include <android/bitmap.h>

#include <QVector>

#include "ReverieCore.h"

#include "reverie_jni_common.h"

extern "C" {

// ============================================================
// Document: 时间 / 帧率 / 播放范围
// ============================================================

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationEnabled(JNIEnv *, jobject)
{
    return core()->animationEnabled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationCurrentTime(JNIEnv *, jobject)
{
    return core()->animationCurrentTime();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setAnimationCurrentTime(JNIEnv *, jobject, jint time, jboolean recordUndo)
{
    core()->setAnimationCurrentTime(time, recordUndo == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationFramerate(JNIEnv *, jobject)
{
    return core()->animationFramerate();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setAnimationFramerate(JNIEnv *, jobject, jint fps)
{
    core()->setAnimationFramerate(fps);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationLength(JNIEnv *, jobject)
{
    return core()->animationLength();
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationPlaybackRange(JNIEnv *env, jobject)
{
    int start = 0;
    int end = 0;
    core()->animationPlaybackRange(&start, &end);

    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    const jint values[2] = { start, end };
    env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setAnimationPlaybackRange(JNIEnv *, jobject, jint start, jint end)
{
    core()->setAnimationPlaybackRange(start, end);
}

// ============================================================
// Track: 图层即轨道
// ============================================================

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerAnimated(JNIEnv *, jobject, jint index)
{
    return core()->layerAnimated(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerAnimatable(JNIEnv *, jobject, jint index)
{
    return core()->layerAnimatable(index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_enableLayerAnimation(JNIEnv *, jobject, jint index)
{
    return core()->enableLayerAnimation(index) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// Keyframe: 帧
// ============================================================

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_hasKeyframe(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->hasKeyframe(layerIndex, time) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_keyframeCount(JNIEnv *, jobject, jint layerIndex)
{
    return core()->keyframeCount(layerIndex);
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_keyframeTimes(JNIEnv *env, jobject, jint layerIndex)
{
    const QVector<int> times = core()->keyframeTimes(layerIndex);

    jintArray out = env->NewIntArray(times.size());
    if (!out) return nullptr;
    if (!times.isEmpty()) {
        env->SetIntArrayRegion(out, 0, times.size(), reinterpret_cast<const jint *>(times.constData()));
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_addKeyframe(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->addKeyframe(layerIndex, time) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_addDuplicateKeyframe(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->addDuplicateKeyframe(layerIndex, time) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_removeKeyframe(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->removeKeyframe(layerIndex, time) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_copyKeyframe(JNIEnv *, jobject, jint layerIndex, jint fromTime, jint toTime)
{
    return core()->copyKeyframe(layerIndex, fromTime, toTime) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cloneKeyframe(JNIEnv *, jobject, jint layerIndex, jint fromTime, jint toTime)
{
    return core()->cloneKeyframe(layerIndex, fromTime, toTime) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveKeyframe(JNIEnv *, jobject, jint layerIndex, jint fromTime, jint toTime)
{
    return core()->moveKeyframe(layerIndex, fromTime, toTime) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_previousKeyframeTime(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->previousKeyframeTime(layerIndex, time);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_nextKeyframeTime(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->nextKeyframeTime(layerIndex, time);
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_keyframeDuration(JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->keyframeDuration(layerIndex, time);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setAllKeyframesDuration(JNIEnv *, jobject, jint layerIndex, jint duration)
{
    return core()->setAllKeyframesDuration(layerIndex, duration) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setSelectedKeyframesDuration(
    JNIEnv *env, jobject, jint layerIndex, jintArray selectedTimes, jint duration)
{
    if (!selectedTimes) return JNI_FALSE;

    const jsize count = env->GetArrayLength(selectedTimes);
    QVector<int> times;
    times.resize(count);
    if (count > 0) {
        env->GetIntArrayRegion(selectedTimes, 0, count, reinterpret_cast<jint *>(times.data()));
    }

    return core()->setSelectedKeyframesDuration(layerIndex, times, duration) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// 帧缩略图
// ============================================================

// 把图层 [layerIndex] 在 [time] 处的关键帧画面渲染进 Bitmap。走
// writeToDevice 拷帧, 不改变文档 currentTime。
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_renderKeyframeThumb(
    JNIEnv *env, jobject, jint layerIndex, jint time, jobject bitmap)
{
    if (!bitmap) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    const bool ok = core()->renderKeyframeThumb(
        layerIndex, time, info.width, info.height, pixels, info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 帧缩略图缓存代际: UI 侧用它判断自己的 (图层, 帧号) 缓存是否整体过期。
JNIEXPORT jlong JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_keyframeThumbGen(JNIEnv *, jobject)
{
    return jlong(core()->keyframeThumbGen());
}

} // extern "C"
