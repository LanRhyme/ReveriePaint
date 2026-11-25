/*
 * ReverieCoreCollab - P2P 茶绘协同引擎 (libdatachannel + 多路 StrokeSession + 独立图层撤销)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef REVERIECORE_COLLAB_H
#define REVERIECORE_COLLAB_H

#include <QByteArray>
#include <QColor>
#include <QHash>
#include <QPointF>
#include <QRect>
#include <QString>
#include <QVector>
#include <kis_types.h>
#include <kis_distance_information.h>
#include <kis_random_source.h>
#include <brushengine/KisPerStrokeRandomSource.h>
#include <brushengine/kis_paintop.h>
#include <brushengine/kis_paintop_preset.h>
#include <mutex>

class ReverieCore;
class KisPainter;

namespace reverie {

struct CollabSample {
    float x = 0.0f;
    float y = 0.0f;
    float pressure = 1.0f;
    float tiltX = 0.0f;
    float tiltY = 0.0f;
    float rotation = 0.0f;
    float timeSec = 0.0f;
};

/** 远端协作者进行中的笔触上下文 (完全隔离本地 m_strokePainter/m_brushPreset) */
struct RemoteStrokeSession {
    QString key;             // peerId + ":" + strokeId
    QString peerId;
    quint32 strokeId = 0;
    QString layerUuid;
    QString presetMd5;
    quint32 seed = 0;
    QColor color = Qt::black;
    qreal brushSize = 20.0;
    qreal opacity = 1.0;
    int toolMode = 0;        // 0=Brush, 1=Eraser, 3=Smudge
    QString previewCompositeOp = QStringLiteral("normal");
    bool isFullDeviceCopy = false; // colorsmudge/deform 需读原图, 预览拷贝底图

    KisPaintDeviceSP previewDev;
    KisPainter *painter = nullptr;
    KisPaintOpSP op;
    KisPaintOpPresetSP preset;
    KisDistanceInformation *distance = nullptr;
    KisRandomSourceSP randomSource;
    KisPerStrokeRandomSourceSP perStrokeRandom;

    bool hasStartedPaint = false;
    bool committed = false;
    quint32 nextSeq = 0;
    QPointF lastPos;
    double lastTimeSec = 0.0;
    QRect dirtyBounds;
    QVector<CollabSample> allSamples;
    QVector<CollabSample> pendingSamples; // 缺少 presetMd5 时暂存, 收到 .kpp 后补画

    ~RemoteStrokeSession();
};

/** 协同模式个人独立撤销记录 (基于图层矩形像素快照, 不干扰全局 KUndo2Stack) */
struct CollabUndoEntry {
    QString layerUuid;
    QRect rect;
    QByteArray beforeCompressed;
    QByteArray afterCompressed;
};

class CollabRuntime;

} // namespace reverie

#endif // REVERIECORE_COLLAB_H
