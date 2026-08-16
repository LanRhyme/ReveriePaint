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

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_savePng(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->savePng(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_exportJpg(JNIEnv *env, jobject, jstring path, jint quality)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->exportJpg(QString::fromUtf8(c), quality);
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_exportPsd(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->exportPsd(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_saveRevp(JNIEnv *env, jobject, jstring path, jstring extraMetaJson, jbyteArray recordingBlob)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const char *metaStr = extraMetaJson ? env->GetStringUTFChars(extraMetaJson, nullptr) : nullptr;
    QByteArray rec;
    if (recordingBlob) {
        const jsize len = env->GetArrayLength(recordingBlob);
        if (len > 0) {
            jbyte *bytes = env->GetByteArrayElements(recordingBlob, nullptr);
            rec = QByteArray(reinterpret_cast<const char *>(bytes), len);
            env->ReleaseByteArrayElements(recordingBlob, bytes, JNI_ABORT);
        }
    }
    const bool ok = core()->saveRevp(QString::fromUtf8(c), metaStr ? QString::fromUtf8(metaStr) : QString(), rec);
    if (metaStr) env->ReleaseStringUTFChars(extraMetaJson, metaStr);
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadRevp(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->loadRevp(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_saveKra(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->saveKra(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_loadPng(JNIEnv *env, jobject, jstring path)
{
    const char *c = env->GetStringUTFChars(path, nullptr);
    const bool ok = core()->loadPng(QString::fromUtf8(c));
    env->ReleaseStringUTFChars(path, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}
}
