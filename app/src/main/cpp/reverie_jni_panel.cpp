/*
 * JNI bridge: comic panel (分格) entry points
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <string.h>

#include <QString>

#include "ReverieCore.h"

#include "reverie_jni_common.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_panelMeta(JNIEnv *env, jobject, jint index)
{
    const QString json = core()->panelMeta(index);
    // 约定: 无/非分格返回 null (Kotlin 侧 String?), 有元数据返回非空串
    if (json.isEmpty()) return nullptr;
    return env->NewStringUTF(json.toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setPanelMeta(JNIEnv *env, jobject, jint index, jstring json)
{
    if (!json) {
        core()->setPanelMeta(index, QString());
        return;
    }
    const char *c = env->GetStringUTFChars(json, nullptr);
    core()->setPanelMeta(index, QString::fromUtf8(c));
    env->ReleaseStringUTFChars(json, c);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_paintPanelQuad(JNIEnv *env, jobject, jint layerIndex,
                                                             jfloatArray cornersXY, jint argb,
                                                             jboolean eraseFill)
{
    if (!cornersXY) return JNI_FALSE;
    const jsize len = env->GetArrayLength(cornersXY);
    if (len < 8) return JNI_FALSE; // 至少一个四边形: x0,y0,x1,y1,x2,y2,x3,y3
    jfloat *xy = env->GetFloatArrayElements(cornersXY, nullptr);
    QVector<QPointF> corners;
    corners.reserve(len / 2);
    for (int i = 0; i + 1 < len; i += 2) {
        corners.append(QPointF(qreal(xy[i]), qreal(xy[i + 1])));
    }
    env->ReleaseFloatArrayElements(cornersXY, xy, JNI_ABORT);
    const bool painted = core()->paintPanelQuad(layerIndex, corners,
                                                quint32(argb), eraseFill == JNI_TRUE);
    return painted ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
