/*
 * JNI bridge: P2P 协同茶绘边界
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <QString>
#include <QVector>
#include <QPointF>
#include "ReverieCore.h"
#include "reverie_jni_common.h"

namespace {

QString jstrToQString(JNIEnv *env, jstring s)
{
    if (!s) return QString();
    const char *c = env->GetStringUTFChars(s, nullptr);
    const QString out = c ? QString::fromUtf8(c) : QString();
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring qStringToJstr(JNIEnv *env, const QString &s)
{
    const QByteArray utf8 = s.toUtf8();
    return env->NewStringUTF(utf8.constData());
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerUuid(JNIEnv *env, jobject, jint index)
{
    return qStringToJstr(env, core()->layerUuid(index));
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerIndexByUuid(JNIEnv *env, jobject, jstring uuid)
{
    return core()->layerIndexByUuid(jstrToQString(env, uuid));
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerOwnerPeerId(JNIEnv *env, jobject, jint index)
{
    return qStringToJstr(env, core()->layerOwnerPeerId(index));
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerOwnerPeerId(JNIEnv *env, jobject, jint index, jstring ownerPeerId)
{
    core()->setLayerOwnerPeerId(index, jstrToQString(env, ownerPeerId));
}

JNIEXPORT jint JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_layerCollabPermission(JNIEnv *, jobject, jint index)
{
    return core()->layerCollabPermission(index);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLayerCollabPermission(JNIEnv *, jobject, jint index, jint permission)
{
    core()->setLayerCollabPermission(index, permission);
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabLayersMetaJson(JNIEnv *env, jobject)
{
    return qStringToJstr(env, core()->collabLayersMetaJson());
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabStartSession(
    JNIEnv *env, jobject,
    jstring roomCode, jstring selfPeerId, jstring selfName,
    jstring hostPeerId, jstring iceServersJson, jstring cacheDir)
{
    const bool ok = core()->collabStartSession(
        jstrToQString(env, roomCode),
        jstrToQString(env, selfPeerId),
        jstrToQString(env, selfName),
        jstrToQString(env, hostPeerId),
        jstrToQString(env, iceServersJson),
        jstrToQString(env, cacheDir));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabStopSession(JNIEnv *, jobject)
{
    core()->collabStopSession();
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabIsActive(JNIEnv *, jobject)
{
    return core()->collabIsActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabCanEditCurrentLayer(JNIEnv *, jobject)
{
    return core()->collabCanEditCurrentLayer() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabOnPeerJoined(
    JNIEnv *env, jobject, jstring peerId, jstring name, jboolean isHostPeer)
{
    core()->collabOnPeerJoined(jstrToQString(env, peerId), jstrToQString(env, name), isHostPeer == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabOnPeerLeft(JNIEnv *env, jobject, jstring peerId)
{
    core()->collabOnPeerLeft(jstrToQString(env, peerId));
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabOnHostChanged(JNIEnv *env, jobject, jstring newHostPeerId)
{
    core()->collabOnHostChanged(jstrToQString(env, newHostPeerId));
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabOnRemoteSignal(
    JNIEnv *env, jobject, jstring fromPeerId, jstring payloadJson)
{
    core()->collabOnRemoteSignal(jstrToQString(env, fromPeerId), jstrToQString(env, payloadJson));
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabSyncOnReconnect(
    JNIEnv *env, jobject, jstring hostPeerId, jstring iceServersJson, jstring peersJson)
{
    core()->collabSyncOnReconnect(
        jstrToQString(env, hostPeerId),
        jstrToQString(env, iceServersJson),
        jstrToQString(env, peersJson));
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabRetryPeer(
    JNIEnv *env, jobject, jstring peerId)
{
    core()->collabRetryPeer(jstrToQString(env, peerId));
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setProjectCollabMeta(
    JNIEnv *env, jobject, jboolean isCollab, jstring roomCode)
{
    core()->setProjectCollabMeta(isCollab == JNI_TRUE, jstrToQString(env, roomCode));
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_isCollabProject(JNIEnv *, jobject)
{
    return core()->isCollabProject() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabProjectRoomCode(JNIEnv *env, jobject)
{
    return qStringToJstr(env, core()->collabProjectRoomCode());
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabDrainInbound(JNIEnv *, jobject)
{
    return core()->collabDrainInbound() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabPollEventsJson(JNIEnv *env, jobject)
{
    return qStringToJstr(env, core()->collabPollEventsJson());
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabPollCursorsJson(JNIEnv *env, jobject)
{
    return qStringToJstr(env, core()->collabPollCursorsJson());
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabNetStatusJson(JNIEnv *env, jobject)
{
    return qStringToJstr(env, core()->collabNetStatusJson());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabSendLocalCursor(
    JNIEnv *, jobject, jfloat docX, jfloat docY, jboolean isDrawing)
{
    core()->collabSendLocalCursor(docX, docY, isDrawing == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_collabTestSimulateRemoteStroke(
    JNIEnv *env, jobject, jstring peerId, jstring layerUuid,
    jint colorArgb, jfloat brushSize, jfloatArray xyPoints, jboolean commit)
{
    QVector<QPointF> pts;
    if (xyPoints) {
        const jsize len = env->GetArrayLength(xyPoints);
        jfloat *raw = env->GetFloatArrayElements(xyPoints, nullptr);
        if (raw) {
            for (jsize i = 0; i + 1 < len; i += 2) {
                pts.append(QPointF(raw[i], raw[i + 1]));
            }
            env->ReleaseFloatArrayElements(xyPoints, raw, JNI_ABORT);
        }
    }
    const bool ok = core()->collabTestSimulateRemoteStroke(
        jstrToQString(env, peerId),
        jstrToQString(env, layerUuid),
        static_cast<quint32>(colorArgb),
        qreal(brushSize),
        pts,
        commit == JNI_TRUE);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
