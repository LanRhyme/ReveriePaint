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

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_configureOnionSkin(
    JNIEnv *, jobject, jboolean enabled, jint prev, jint next, jint maxOpacity,
    jint tintFactor, jint tintBackwardArgb, jint tintForwardArgb)
{
    core()->configureOnionSkin(enabled == JNI_TRUE, prev, next, maxOpacity, tintFactor,
                               tintBackwardArgb, tintForwardArgb);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_anyLayerOnionSkin(JNIEnv *, jobject)
{
    return core()->anyLayerOnionSkin() ? JNI_TRUE : JNI_FALSE;
}

// 读回洋葱皮全局配置: [backwardArgb, forwardArgb, tintFactor]
JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_onionSkinConfig(JNIEnv *env, jobject)
{
    int backward = 0;
    int forward = 0;
    core()->onionSkinTintColors(&backward, &forward);
    const int tint = core()->onionSkinTintFactor();

    jintArray out = env->NewIntArray(3);
    if (!out) return nullptr;
    const jint values[3] = { backward, forward, tint };
    env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}

// 丢弃洋葱皮缓存。帧内像素改动 (落笔 / 填充 / 滤镜) 之后必须调,
// 否则相邻帧上叠加的洋葱皮不会随内容更新。
JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_flushOnionSkinCaches(JNIEnv *, jobject)
{
    core()->flushOnionSkinCaches();
}

// 播放期洋葱皮抑制: 播放开始置 true (隐藏洋葱皮), 暂停/停止置 false (还原)。
// 状态真身在 C++ (m_onionSkinSuppressed), Kotlin 侧只在正确时机调用。
JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setOnionSkinSuppressed(
    JNIEnv *, jobject, jboolean suppressed)
{
    core()->setOnionSkinSuppressed(suppressed == JNI_TRUE);
}

// 导入资源列表 (名称数组)
JNIEXPORT jobjectArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_revAssetNames(JNIEnv *env, jobject)
{
    const QVector<QString> names = core()->revAssetNames();
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(jsize(names.size()), strClass, nullptr);
    for (int i = 0; i < names.size(); ++i) {
        env->SetObjectArrayElement(out, i, env->NewStringUTF(names[i].toUtf8().constData()));
    }
    return out;
}

// 取回指定导入资源的字节
JNIEXPORT jbyteArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_revAssetBytes(JNIEnv *env, jobject, jstring name)
{
    if (!name) return nullptr;
    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    const QByteArray data = core()->revAssetBytes(QString::fromUtf8(nameChars));
    env->ReleaseStringUTFChars(name, nameChars);
    if (data.isEmpty()) return nullptr;
    jbyteArray out = env->NewByteArray(jsize(data.size()));
    env->SetByteArrayRegion(out, 0, jsize(data.size()), reinterpret_cast<const jbyte *>(data.constData()));
    return out;
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

// 取走并清空帧缩略图"精准失效"脏帧集合, 交替 [layer0, time0, layer1, ...]。
// UI 侧 refreshFrameThumbs 时调用: 命中脏集合的帧强制重渲染,
// 其余帧在代际未变时照常复用 (避免落笔后全量重渲染所有缩略图)。
JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_takeDirtyKeyframeThumbs(JNIEnv *env, jobject)
{
    const QVector<int> dirty = core()->takeDirtyKeyframeThumbs();
    jintArray arr = env->NewIntArray(jsize(dirty.size()));
    if (arr == nullptr) return nullptr;
    if (!dirty.isEmpty()) {
        env->SetIntArrayRegion(arr, 0, jsize(dirty.size()), dirty.constData());
    }
    return arr;
}

// 把位图作为关键帧导入指定轨道的指定帧 (图像/视频抽帧导入用)。
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_importKeyframeFromBitmap(
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
    const bool ok = core()->importKeyframeFromBitmap(
        layerIndex, time, info.width, info.height, pixels, info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 存入导入资源 (音频等二进制), 保存 .revp 时写入 assets/<name>
JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_storeRevAsset(JNIEnv *env, jobject, jstring name, jbyteArray data)
{
    if (!name || !data) return;
    const jsize len = env->GetArrayLength(data);
    if (len <= 0) return;
    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    QByteArray buf(len, 0);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buf.data()));
    core()->storeRevAsset(QString::fromUtf8(nameChars), buf);
    env->ReleaseStringUTFChars(name, nameChars);
}

// 自动中割 (Auto In-betweening)
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationGenerateInbetween(
    JNIEnv *, jobject, jint layerIndex, jint timeA, jint timeB, jint targetTime, jfloat t)
{
    return core()->generateInbetween(layerIndex, timeA, timeB, targetTime, t) ? JNI_TRUE : JNI_FALSE;
}

// 关键帧色标与标签
JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationKeyframeTag(
    JNIEnv *, jobject, jint layerIndex, jint time)
{
    return core()->keyframeTag(layerIndex, time);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationSetKeyframeTag(
    JNIEnv *, jobject, jint layerIndex, jint time, jint tag)
{
    core()->setKeyframeTag(layerIndex, time, tag);
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationAllKeyframeTags(JNIEnv *env, jobject)
{
    const QHash<quint64, int> tags = core()->allKeyframeTags();
    jintArray arr = env->NewIntArray(jsize(tags.size() * 3));
    if (!arr) return nullptr;
    QVector<jint> buf;
    buf.reserve(tags.size() * 3);
    for (auto it = tags.constBegin(); it != tags.constEnd(); ++it) {
        buf.append(jint(quint32(it.key() >> 32)));
        buf.append(jint(quint32(it.key() & 0xFFFFFFFFULL)));
        buf.append(jint(it.value()));
    }
    if (!buf.isEmpty()) {
        env->SetIntArrayRegion(arr, 0, jsize(buf.size()), buf.constData());
    }
    return arr;
}

// 轨道末帧保持时长
JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationLastFrameHold(
    JNIEnv *, jobject, jint layerIndex)
{
    return core()->lastFrameHold(layerIndex);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationSetLastFrameHold(
    JNIEnv *, jobject, jint layerIndex, jint hold, jboolean recordUndo)
{
    core()->setLastFrameHold(layerIndex, hold, recordUndo == JNI_TRUE);
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_animationAllLastFrameHolds(JNIEnv *env, jobject)
{
    const QHash<int, int> holds = core()->allLastFrameHolds();
    jintArray arr = env->NewIntArray(jsize(holds.size() * 2));
    if (!arr) return nullptr;
    QVector<jint> buf;
    buf.reserve(holds.size() * 2);
    for (auto it = holds.constBegin(); it != holds.constEnd(); ++it) {
        buf.append(jint(it.key()));
        buf.append(jint(it.value()));
    }
    if (!buf.isEmpty()) {
        env->SetIntArrayRegion(arr, 0, jsize(buf.size()), buf.constData());
    }
    return arr;
}

} // extern "C"

