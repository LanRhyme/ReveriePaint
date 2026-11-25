/*
 * ReverieCoreCollab - P2P 茶绘协同引擎 (libdatachannel + 多路 StrokeSession + 独立图层撤销)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCoreInternal.h"
#include "ReverieCoreCollab.h"
#include "ReverieCoreLayerExtras.h"
#include "StrokePaintInfo.h"
#include "PaintOpTraits.h"

#include <rtc/rtc.hpp>

#include <QCryptographicHash>
#include <QDataStream>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QIODevice>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUuid>
#include <QtEndian>
#include <algorithm>
#include <cmath>
#include <memory>

namespace {

constexpr quint32 kCollabProtoVersion = 1;
constexpr int kSnapshotChunkBytes = 16 * 1024;
constexpr int kBrushChunkBytes = 16 * 1024;
constexpr int kStarMaxPeers = 10;
constexpr int kUndoCap = 30;
constexpr int kHostSeqHistoryLimit = 256;

enum WireMsgType : quint8 {
    MsgCursor = 0x01,
    MsgStrokeDelta = 0x02,

    MsgHello = 0x10,
    MsgSeqWrap = 0x11,
    MsgSnapshotBegin = 0x12,
    MsgSnapshotChunk = 0x13,
    MsgStrokeBegin = 0x14,
    MsgStrokeCommit = 0x15,
    MsgStrokeCancel = 0x16,
    MsgBrushReq = 0x17,
    MsgBrushChunk = 0x18,
    MsgLayerOp = 0x19,
    MsgTilePatch = 0x1A,
    MsgTopologyRelay = 0x1B,
};

struct InboundPacket {
    QString fromPeerId;
    QByteArray data;
    bool isLiveChannel = false;
};

struct CursorEntry {
    QString peerId;
    QString name;
    float docX = 0.0f;
    float docY = 0.0f;
    float brushSize = 20.0f;
    quint32 colorArgb = 0xFF000000u;
    bool isDrawing = false;
    qint64 updatedAtMs = 0;
};

struct PeerLink {
    QString peerId;
    QString name;
    bool isHostPeer = false;
    bool initiatedByUs = false;
    bool ctrlOpen = false;
    bool liveOpen = false;
    bool snapshotSent = false;
    bool isRelayChild = false;
    QString relayViaPeerId;
    std::shared_ptr<rtc::PeerConnection> pc;
    std::shared_ptr<rtc::DataChannel> ctrl;
    std::shared_ptr<rtc::DataChannel> live;
};

struct IncomingSnapshotAssembly {
    quint64 docSeq = 0;
    quint32 totalBytes = 0;
    quint32 totalChunks = 0;
    QByteArray expectedSha256;
    QVector<QByteArray> chunks;
    quint32 receivedChunks = 0;
    bool active = false;
};

struct IncomingBrushAssembly {
    QString presetMd5;
    quint32 totalBytes = 0;
    quint32 totalChunks = 0;
    QVector<QByteArray> chunks;
    quint32 receivedChunks = 0;
};

struct SuppressEmitGuard {
    bool &flag;
    bool prev;
    explicit SuppressEmitGuard(bool &f) : flag(f), prev(f) { flag = true; }
    ~SuppressEmitGuard() { flag = prev; }
};

QByteArray packMsgHeader(quint8 type)
{
    QByteArray buf;
    buf.reserve(64);
    buf.append(char(type));
    return buf;
}

void writeUtf8(QDataStream &ds, const QString &s)
{
    ds << s.toUtf8();
}

QString readUtf8(QDataStream &ds)
{
    QByteArray b;
    ds >> b;
    return QString::fromUtf8(b);
}

QByteArray wrapWithSeq(quint64 seq, const QByteArray &inner)
{
    QByteArray out = packMsgHeader(MsgSeqWrap);
    QDataStream ds(&out, QIODevice::Append);
    ds.setByteOrder(QDataStream::LittleEndian);
    ds << seq << inner;
    return out;
}

bool parseIceServers(const QString &jsonStr, rtc::Configuration &cfg)
{
    QJsonDocument doc = QJsonDocument::fromJson(jsonStr.toUtf8());
    if (!doc.isArray()) return false;
    const QJsonArray arr = doc.array();
    for (const QJsonValue &v : arr) {
        if (!v.isObject()) continue;
        const QJsonObject obj = v.toObject();
        const QString user = obj.value("username").toString();
        const QString cred = obj.value("credential").toString();

        QStringList urls;
        const QJsonValue uVal = obj.value("urls");
        if (uVal.isString()) {
            urls.append(uVal.toString());
        } else if (uVal.isArray()) {
            for (const QJsonValue &uv : uVal.toArray()) {
                if (uv.isString()) urls.append(uv.toString());
            }
        }
        for (const QString &u : urls) {
            if (u.isEmpty()) continue;
            try {
                rtc::IceServer srv(u.toStdString());
                if (!user.isEmpty()) srv.username = user.toStdString();
                if (!cred.isEmpty()) srv.password = cred.toStdString();
                cfg.iceServers.push_back(std::move(srv));
            } catch (...) {
            }
        }
    }
    return true;
}

} // namespace

namespace reverie {

RemoteStrokeSession::~RemoteStrokeSession()
{
    op = nullptr;
    delete painter;
    painter = nullptr;
    delete distance;
    distance = nullptr;
}

class CollabRuntime {
public:
    explicit CollabRuntime(ReverieCore *core) : m_core(core) {}
    ~CollabRuntime() { stop(); }

    const QString &selfPeerId() const { return m_selfPeerId; }
    bool isActive() const { return m_active; }

    bool start(const QString &roomCode,
               const QString &selfPeerId,
               const QString &selfName,
               const QString &hostPeerId,
               const QString &iceServersJson,
               const QString &cacheDir)
    {
        stop();
        static std::once_flag s_rtcInitOnce;
        std::call_once(s_rtcInitOnce, []() {
            rtc::InitLogger(rtc::LogLevel::Warning, nullptr);
        });

        m_roomCode = roomCode;
        m_selfPeerId = selfPeerId;
        m_selfName = selfName;
        m_hostPeerId = hostPeerId;
        m_isHost = (selfPeerId == hostPeerId);
        m_iceServersJson = iceServersJson;
        m_cacheDir = cacheDir;
        m_brushCacheDir = cacheDir + QStringLiteral("/brushes");
        m_snapshotTempPath = cacheDir + QStringLiteral("/snapshot_tmp.revp");
        QDir().mkpath(m_brushCacheDir);

        m_hostGlobalSeq = 0;
        m_lastAppliedSeq = 0;
        m_localStrokeSeq = 0;
        m_seqHistory.clear();
        m_undoEntries.clear();
        m_undoIdx = 0;
        m_active = true;
        return true;
    }

    void stop()
    {
        m_active = false;
        clearDocumentBoundState();

        QVector<std::shared_ptr<rtc::PeerConnection>> pcsToClose;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            for (auto it = m_peers.begin(); it != m_peers.end(); ++it) {
                if (it->ctrl) {
                    try { it->ctrl->close(); } catch (...) {}
                }
                if (it->live) {
                    try { it->live->close(); } catch (...) {}
                }
                if (it->pc) {
                    pcsToClose.append(it->pc);
                }
            }
            m_peers.clear();
            m_relayChildren.clear();
            m_relayForwarderId.clear();
        }
        for (auto &pc : pcsToClose) {
            try {
                pc->resetCallbacks();
                pc->close();
            } catch (...) {
            }
        }

        {
            std::lock_guard<std::mutex> lock(m_inMutex);
            m_inboundQueue.clear();
        }
        {
            std::lock_guard<std::mutex> lock(m_uiMutex);
            m_uiEvents.clear();
        }
        {
            std::lock_guard<std::mutex> lock(m_cursorMutex);
            m_cursors.clear();
        }
        m_presetCache.clear();
        m_presetBytesCache.clear();
        m_incomingBrushes.clear();
    }

    void clearDocumentBoundState()
    {
        qDeleteAll(m_remoteStrokes);
        m_remoteStrokes.clear();
        m_localBeforeDev = nullptr;
        m_localBeforeLayerUuid.clear();
        m_localStrokeActive = false;
        m_undoEntries.clear();
        m_undoIdx = 0;
        m_incomingSnapshot = IncomingSnapshotAssembly{};
    }

    void onPeerJoined(const QString &peerId, const QString &name, bool isHostPeer)
    {
        if (!m_active || peerId.isEmpty() || peerId == m_selfPeerId) return;

        bool needCreatePc = false;
        bool shouldInitiate = false;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            if (!m_peers.contains(peerId)) {
                PeerLink link;
                link.peerId = peerId;
                link.name = name;
                link.isHostPeer = isHostPeer;
                m_peers.insert(peerId, std::move(link));
                needCreatePc = true;
            } else {
                m_peers[peerId].name = name;
                m_peers[peerId].isHostPeer = isHostPeer;
            }
            // 星型策略: 非房主端只与房主直连 (除非作为大房间被分配二跳转发节点);
            // 防对称 glare 规则: 永远由非房主连房主, 若同级互联则 peerId 小的一方发起 offer
            if (m_isHost) {
                shouldInitiate = false;
            } else if (peerId == m_hostPeerId) {
                shouldInitiate = true;
            } else {
                needCreatePc = false;
            }
        }

        if (needCreatePc && shouldInitiate) {
            ensurePeerConnection(peerId, true);
        }
        if (m_isHost) {
            recomputeTopologyIfNeeded();
        }
    }

    void onPeerLeft(const QString &peerId)
    {
        if (peerId.isEmpty()) return;
        std::shared_ptr<rtc::PeerConnection> pcToClose;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto it = m_peers.find(peerId);
            if (it != m_peers.end()) {
                if (it->ctrl) {
                    try { it->ctrl->close(); } catch (...) {}
                }
                if (it->live) {
                    try { it->live->close(); } catch (...) {}
                }
                pcToClose = it->pc;
                m_peers.erase(it);
            }
        }
        if (pcToClose) {
            try {
                pcToClose->resetCallbacks();
                pcToClose->close();
            } catch (...) {
            }
        }
        {
            std::lock_guard<std::mutex> lock(m_cursorMutex);
            m_cursors.remove(peerId);
        }
        cancelRemoteStrokesForPeer(peerId);
        if (m_isHost) {
            recomputeTopologyIfNeeded();
        }
    }

    void onHostChanged(const QString &newHostPeerId)
    {
        if (!m_active || newHostPeerId.isEmpty()) return;
        const bool wasHost = m_isHost;
        m_hostPeerId = newHostPeerId;
        m_isHost = (m_selfPeerId == newHostPeerId);
        if (m_isHost && !wasHost) {
            m_hostGlobalSeq = m_lastAppliedSeq;
            // 新房主等待其余成员建立连接
            pushUiEvent(QJsonObject{
                {"type", QStringLiteral("host_migrated")},
                {"hostPeerId", newHostPeerId},
            });
        } else if (!m_isHost && newHostPeerId != m_selfPeerId) {
            // 若还未直连新房主, 主动发起连接
            bool connected = false;
            {
                std::lock_guard<std::mutex> lock(m_netMutex);
                auto it = m_peers.constFind(newHostPeerId);
                connected = (it != m_peers.constEnd() && it->ctrlOpen);
            }
            if (!connected) {
                ensurePeerConnection(newHostPeerId, true);
            }
        }
    }

    void onRemoteSignal(const QString &fromPeerId, const QString &payloadJson)
    {
        if (!m_active || fromPeerId.isEmpty() || fromPeerId == m_selfPeerId) return;
        const QJsonDocument doc = QJsonDocument::fromJson(payloadJson.toUtf8());
        if (!doc.isObject()) return;
        const QJsonObject obj = doc.object();

        auto pc = ensurePeerConnection(fromPeerId, false);
        if (!pc) return;

        if (obj.contains("sdp")) {
            const QString sdp = obj.value("sdp").toString();
            const QString typeStr = obj.value("type").toString();
            try {
                rtc::Description::Type descType = rtc::Description::Type::Unspec;
                if (typeStr == QLatin1String("offer")) descType = rtc::Description::Type::Offer;
                else if (typeStr == QLatin1String("answer")) descType = rtc::Description::Type::Answer;
                pc->setRemoteDescription(rtc::Description(sdp.toStdString(), descType));
            } catch (const std::exception &e) {
                RPC_LOG("Collab setRemoteDescription error from=%s: %s", fromPeerId.toUtf8().constData(), e.what());
            }
        } else if (obj.contains("candidate")) {
            const QString cand = obj.value("candidate").toString();
            const QString mid = obj.value("sdpMid").toString();
            try {
                pc->addRemoteCandidate(rtc::Candidate(cand.toStdString(), mid.toStdString()));
            } catch (const std::exception &e) {
                RPC_LOG("Collab addRemoteCandidate error from=%s: %s", fromPeerId.toUtf8().constData(), e.what());
            }
        }
    }

    void sendLocalCursor(float docX, float docY, bool isDrawing)
    {
        if (!m_active) return;
        QByteArray pkt = packMsgHeader(MsgCursor);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        writeUtf8(ds, m_selfPeerId);
        ds << docX << docY << float(m_core->brushSize())
           << quint32(m_core->brushColor().rgba()) << quint8(isDrawing ? 1 : 0);
        sendLivePacket(pkt);
    }

    void onLocalStrokeBegin(int seed)
    {
        if (!m_active || !m_core->m_document) return;
        const int idx = m_core->m_currentLayer;
        if (idx <= 0 || idx >= m_core->m_layers.size()) return;
        const QString layerUuid = m_core->m_layers[idx].uuid;
        if (layerUuid.isEmpty()) return;

        if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[idx].node)) {
            if (pl->paintDevice()) {
                m_localBeforeDev = new KisPaintDevice(*pl->paintDevice());
                m_localBeforeLayerUuid = layerUuid;
            }
        }

        m_localStrokeSeq++;
        m_localStrokeActive = true;
        m_localStrokeLayerUuid = layerUuid;
        m_localSentSampleCount = 0;

        QString presetMd5;
        QByteArray kppBytes;
        serializeCurrentPreset(presetMd5, kppBytes);

        const QPointF startPos = m_core->m_strokeSamples.isEmpty()
            ? m_core->m_strokeStartImg
            : m_core->m_strokeSamples.first().imgPos;

        QByteArray pkt = packMsgHeader(MsgStrokeBegin);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(m_localStrokeSeq);
        writeUtf8(ds, layerUuid);
        writeUtf8(ds, presetMd5);
        ds << quint32(seed)
           << quint32(m_core->m_strokeColor.rgba())
           << float(m_core->m_brushSize)
           << float(m_core->m_strokeOpacity)
           << quint8(int(m_core->m_toolMode))
           << float(startPos.x()) << float(startPos.y())
           << float(m_core->m_lastPressure)
           << float(m_core->m_lastTiltX)
           << float(m_core->m_lastTiltY)
           << float(m_core->m_lastRotation);
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void onLocalStrokeFlushed()
    {
        if (!m_active || !m_localStrokeActive) return;
        const int total = m_core->m_collabPendingSamples.size();
        if (total <= m_localSentSampleCount) return;

        const int startIdx = m_localSentSampleCount;
        const int count = total - startIdx;
        m_localSentSampleCount = total;

        QByteArray pkt = packMsgHeader(MsgStrokeDelta);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(m_localStrokeSeq) << quint32(startIdx) << quint16(count);
        for (int i = startIdx; i < total; ++i) {
            const ReverieCore::StrokeSample &s = m_core->m_collabPendingSamples.at(i);
            ds << float(s.imgPos.x()) << float(s.imgPos.y()) << float(s.pressure)
               << float(s.tiltX) << float(s.tiltY) << float(s.rotation) << float(s.time);
        }
        sendLivePacket(pkt);
    }

    void onLocalStrokeEnd(const QRect &dirtyRect)
    {
        if (!m_active || !m_localStrokeActive) {
            m_localBeforeDev = nullptr;
            return;
        }
        onLocalStrokeFlushed();

        // 记录个人独立图层像素撤销栈 (支持 Magma 个人互不干扰撤回)
        const int idx = m_core->layerIndexByUuid(m_localBeforeLayerUuid);
        if (m_localBeforeDev && idx >= 0 && idx < m_core->m_layers.size() && !dirtyRect.isEmpty()) {
            if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[idx].node)) {
                if (pl->paintDevice()) {
                    const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
                    const QRect r = dirtyRect.intersected(docRect);
                    if (!r.isEmpty()) {
                        pushPixelUndo(m_localBeforeLayerUuid, r, m_localBeforeDev, pl->paintDevice());
                    }
                }
            }
        }
        m_localBeforeDev = nullptr;
        m_localBeforeLayerUuid.clear();

        // Commit 包附带整笔完整采样点 (兜底不可靠通道万一丢失的片段)
        const int total = m_core->m_collabPendingSamples.size();
        QByteArray pkt = packMsgHeader(MsgStrokeCommit);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(m_localStrokeSeq) << quint32(total);
        for (int i = 0; i < total; ++i) {
            const ReverieCore::StrokeSample &s = m_core->m_collabPendingSamples.at(i);
            ds << float(s.imgPos.x()) << float(s.imgPos.y()) << float(s.pressure)
               << float(s.tiltX) << float(s.tiltY) << float(s.rotation) << float(s.time);
        }
        m_core->m_collabPendingSamples.clear();
        m_localStrokeActive = false;
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void onLocalStrokeCancel()
    {
        m_localBeforeDev = nullptr;
        m_localBeforeLayerUuid.clear();
        if (!m_active || !m_localStrokeActive) return;
        m_localStrokeActive = false;
        QByteArray pkt = packMsgHeader(MsgStrokeCancel);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(m_localStrokeSeq);
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/false);
    }

    void emitLayerOp(int opKind, int index, const QString &extraStr, double extraVal, int extraInt)
    {
        if (!m_active) return;

        // 协同模式下新建图层自动设定本人为默认拥有者 (Magma 专属图层默认值)
        QString targetUuid;
        QString ownerId = m_selfPeerId;
        int perm = 1; // OwnerOnly
        if (opKind == ReverieCore::CollabLayerAddPaint || opKind == ReverieCore::CollabLayerAddGroup) {
            if (index >= 0 && index < m_core->m_layers.size() && m_core->m_layers[index].node) {
                std::lock_guard<std::mutex> mirrorLock(m_core->m_layerMirrorMutex);
                m_core->m_layers[index].ownerPeerId = m_selfPeerId;
                m_core->m_layers[index].collabPermission = 1;
                reverie::setLayerProperty(m_core->m_layers[index].node, "reverie_owner", m_selfPeerId);
                reverie::setLayerProperty(m_core->m_layers[index].node, "reverie_collab_perm", 1);
                targetUuid = m_core->m_layers[index].uuid;
            }
        } else if (opKind == ReverieCore::CollabLayerRemove) {
            targetUuid = extraStr;
        } else if (index >= 0 && index < m_core->m_layers.size()) {
            targetUuid = m_core->m_layers[index].uuid;
            ownerId = m_core->m_layers[index].ownerPeerId;
            perm = m_core->m_layers[index].collabPermission;
        }
        if (targetUuid.isEmpty()) return;

        QString aboveUuid;
        if ((opKind == ReverieCore::CollabLayerAddPaint || opKind == ReverieCore::CollabLayerAddGroup) &&
            index > 0 && index - 1 < m_core->m_layers.size()) {
            aboveUuid = m_core->m_layers[index - 1].uuid;
        }

        QByteArray pkt = packMsgHeader(MsgLayerOp);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, m_selfPeerId);
        ds << quint8(opKind);
        writeUtf8(ds, targetUuid);
        writeUtf8(ds, extraStr);
        ds << extraVal << qint32(extraInt);
        writeUtf8(ds, ownerId);
        ds << quint8(perm);
        writeUtf8(ds, aboveUuid);

        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
        pushUiEvent(QJsonObject{{"type", QStringLiteral("layers_changed")}});
    }

    bool canUndo() const { return m_undoIdx > 0; }
    bool canRedo() const { return m_undoIdx < m_undoEntries.size(); }
    int undoCount() const { return m_undoIdx; }
    int redoCount() const { return m_undoEntries.size() - m_undoIdx; }

    bool undo()
    {
        if (!canUndo()) return false;
        const CollabUndoEntry &e = m_undoEntries.at(m_undoIdx - 1);
        if (!applyCompressedPatchToLayer(e.layerUuid, e.rect, e.beforeCompressed, true)) {
            return false;
        }
        --m_undoIdx;
        return true;
    }

    bool redo()
    {
        if (!canRedo()) return false;
        const CollabUndoEntry &e = m_undoEntries.at(m_undoIdx);
        if (!applyCompressedPatchToLayer(e.layerUuid, e.rect, e.afterCompressed, true)) {
            return false;
        }
        ++m_undoIdx;
        return true;
    }

    bool drainInbound()
    {
        if (!m_active) return false;
        QVector<InboundPacket> batch;
        {
            std::lock_guard<std::mutex> lock(m_inMutex);
            if (m_inboundQueue.isEmpty()) return false;
            batch.swap(m_inboundQueue);
        }

        bool visualDirty = false;
        for (const InboundPacket &pkt : batch) {
            if (handleInboundPacket(pkt)) {
                visualDirty = true;
            }
        }
        return visualDirty;
    }

    QString pollEventsJson()
    {
        QVector<QString> list;
        {
            std::lock_guard<std::mutex> lock(m_uiMutex);
            if (m_uiEvents.isEmpty()) return QStringLiteral("[]");
            list.swap(m_uiEvents);
        }
        QJsonArray arr;
        for (const QString &s : list) {
            const QJsonDocument d = QJsonDocument::fromJson(s.toUtf8());
            if (d.isObject()) arr.append(d.object());
        }
        return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
    }

    QString pollCursorsJson()
    {
        const qint64 nowMs = QDateTime::currentMSecsSinceEpoch();
        QJsonArray arr;
        std::lock_guard<std::mutex> lock(m_cursorMutex);
        for (auto it = m_cursors.begin(); it != m_cursors.end();) {
            if (nowMs - it->updatedAtMs > 12000) {
                it = m_cursors.erase(it);
                continue;
            }
            arr.append(QJsonObject{
                {"peerId", it->peerId},
                {"name", it->name},
                {"docX", double(it->docX)},
                {"docY", double(it->docY)},
                {"brushSize", double(it->brushSize)},
                {"colorArgb", qint64(it->colorArgb)},
                {"isDrawing", it->isDrawing},
                {"updatedAtMs", qint64(it->updatedAtMs)},
            });
            ++it;
        }
        return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
    }

    bool hasRemotePreviewForLayer(const QString &layerUuid) const
    {
        if (layerUuid.isEmpty() || m_remoteStrokes.isEmpty()) return false;
        for (auto it = m_remoteStrokes.cbegin(); it != m_remoteStrokes.cend(); ++it) {
            const RemoteStrokeSession *s = it.value();
            if (s && s->previewDev && s->layerUuid == layerUuid) {
                return true;
            }
        }
        return false;
    }

    bool hasActiveRemoteStrokeBelow(int stopIdx) const
    {
        if (m_remoteStrokes.isEmpty()) return false;
        for (auto it = m_remoteStrokes.cbegin(); it != m_remoteStrokes.cend(); ++it) {
            const RemoteStrokeSession *s = it.value();
            if (!s || !s->previewDev) continue;
            const int idx = m_core->layerIndexByUuid(s->layerUuid);
            if (idx >= 0 && idx < stopIdx) return true;
        }
        return false;
    }

    void compositeRemotePreviews(const QString &layerUuid, KisPaintDeviceSP scratch,
                                 const QRect &r, const QBitArray &alphaLockFlags)
    {
        if (!scratch || r.isEmpty()) return;
        for (auto it = m_remoteStrokes.cbegin(); it != m_remoteStrokes.cend(); ++it) {
            const RemoteStrokeSession *s = it.value();
            if (!s || !s->previewDev || s->layerUuid != layerUuid) continue;
            const QRect ext = s->previewDev->extent();
            if (ext.isEmpty() || !ext.intersects(r)) continue;

            KisPainter painter(scratch);
            painter.setCompositeOpId(s->isFullDeviceCopy ? QString(COMPOSITE_COPY) : s->previewCompositeOp);
            painter.setOpacityF(qBound<qreal>(0.0, s->opacity, 1.0));
            if (!alphaLockFlags.isEmpty()) {
                painter.setChannelFlags(alphaLockFlags);
            }
            painter.bitBlt(r.topLeft(), s->previewDev, r);
            painter.end();
        }
    }

    bool simulateRemoteStroke(const QString &peerId, const QString &layerUuid,
                              quint32 colorArgb, qreal brushSize,
                              const QVector<QPointF> &points, bool commit)
    {
        if (!m_core->m_document || points.isEmpty()) return false;
        const quint32 sid = ++m_simStrokeSeq;
        QString presetMd5;
        QByteArray dummy;
        serializeCurrentPreset(presetMd5, dummy);

        const QPointF p0 = points.first();
        handleStrokeBegin(peerId, sid, layerUuid, presetMd5, 1337u, colorArgb,
                          float(brushSize), 1.0f, 0, float(p0.x()), float(p0.y()),
                          1.0f, 0.0f, 0.0f, 0.0f);

        QVector<CollabSample> batch;
        for (int i = 0; i < points.size(); ++i) {
            CollabSample cs;
            cs.x = float(points[i].x());
            cs.y = float(points[i].y());
            cs.pressure = 1.0f;
            cs.timeSec = float(i) * 0.008f;
            batch.append(cs);
        }
        handleStrokeSamples(peerId, sid, 0, batch);
        if (commit) {
            handleStrokeCommit(peerId, sid, batch);
        }
        return true;
    }

private:
    ReverieCore *m_core = nullptr;
    bool m_active = false;
    bool m_isHost = false;
    QString m_roomCode;
    QString m_selfPeerId;
    QString m_selfName;
    QString m_hostPeerId;
    QString m_iceServersJson;
    QString m_cacheDir;
    QString m_brushCacheDir;
    QString m_snapshotTempPath;

    quint64 m_hostGlobalSeq = 0;
    quint64 m_lastAppliedSeq = 0;
    quint32 m_localStrokeSeq = 0;
    quint32 m_simStrokeSeq = 0;

    std::mutex m_netMutex;
    QHash<QString, PeerLink> m_peers;
    QString m_relayForwarderId;
    QSet<QString> m_relayChildren;

    std::mutex m_inMutex;
    QVector<InboundPacket> m_inboundQueue;

    std::mutex m_uiMutex;
    QVector<QString> m_uiEvents;

    std::mutex m_cursorMutex;
    QHash<QString, CursorEntry> m_cursors;

    QHash<QString, RemoteStrokeSession *> m_remoteStrokes;
    QHash<QString, KisPaintOpPresetSP> m_presetCache;
    QHash<QString, QByteArray> m_presetBytesCache;
    QHash<QString, IncomingBrushAssembly> m_incomingBrushes;
    IncomingSnapshotAssembly m_incomingSnapshot;

    // 房主短历史缓存, 用于大房间/新加入对齐增量
    QVector<QPair<quint64, QByteArray>> m_seqHistory;

    // 本地当前绘制前像素快照 (COW) 与独立撤销栈
    bool m_localStrokeActive = false;
    QString m_localStrokeLayerUuid;
    int m_localSentSampleCount = 0;
    KisPaintDeviceSP m_localBeforeDev;
    QString m_localBeforeLayerUuid;
    QVector<CollabUndoEntry> m_undoEntries;
    int m_undoIdx = 0;

    void pushUiEvent(const QJsonObject &obj)
    {
        const QString raw = QString::fromUtf8(QJsonDocument(obj).toJson(QJsonDocument::Compact));
        std::lock_guard<std::mutex> lock(m_uiMutex);
        m_uiEvents.append(raw);
    }

    std::shared_ptr<rtc::PeerConnection> ensurePeerConnection(const QString &remotePeerId, bool initiateOffer)
    {
        std::shared_ptr<rtc::PeerConnection> existingPc;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto it = m_peers.find(remotePeerId);
            if (it != m_peers.end() && it->pc) {
                const auto st = it->pc->state();
                if (st != rtc::PeerConnection::State::Closed && st != rtc::PeerConnection::State::Failed) {
                    return it->pc;
                }
                it->pc->resetCallbacks();
                it->pc.reset();
                it->ctrl.reset();
                it->live.reset();
                it->ctrlOpen = false;
                it->liveOpen = false;
            }
        }

        rtc::Configuration cfg;
        parseIceServers(m_iceServersJson, cfg);

        std::shared_ptr<rtc::PeerConnection> pc;
        try {
            pc = std::make_shared<rtc::PeerConnection>(cfg);
        } catch (const std::exception &e) {
            RPC_LOG("Collab PeerConnection create failed: %s", e.what());
            return nullptr;
        }

        pc->onLocalDescription([this, remotePeerId](rtc::Description desc) {
            const char *t = "offer";
            if (desc.type() == rtc::Description::Type::Answer) t = "answer";
            else if (desc.type() == rtc::Description::Type::Pranswer) t = "pranswer";
            pushUiEvent(QJsonObject{
                {"type", QStringLiteral("signal_out")},
                {"to", remotePeerId},
                {"payload", QJsonObject{
                    {"type", QString::fromLatin1(t)},
                    {"sdp", QString::fromStdString(std::string(desc))},
                }},
            });
        });

        pc->onLocalCandidate([this, remotePeerId](rtc::Candidate cand) {
            pushUiEvent(QJsonObject{
                {"type", QStringLiteral("signal_out")},
                {"to", remotePeerId},
                {"payload", QJsonObject{
                    {"candidate", QString::fromStdString(cand.candidate())},
                    {"sdpMid", QString::fromStdString(cand.mid())},
                }},
            });
        });

        pc->onStateChange([this, remotePeerId](rtc::PeerConnection::State st) {
            const bool isConnected = (st == rtc::PeerConnection::State::Connected);
            const bool isFailed = (st == rtc::PeerConnection::State::Failed ||
                                   st == rtc::PeerConnection::State::Closed);
            if (isConnected || isFailed) {
                pushUiEvent(QJsonObject{
                    {"type", QStringLiteral("peer_p2p_state")},
                    {"peerId", remotePeerId},
                    {"connected", isConnected},
                });
            }
        });

        pc->onDataChannel([this, remotePeerId](std::shared_ptr<rtc::DataChannel> dc) {
            if (!dc) return;
            const std::string lbl = dc->label();
            const bool isLive = (lbl == "collab-live");
            bindDataChannel(remotePeerId, dc, isLive);
        });

        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            PeerLink &link = m_peers[remotePeerId];
            link.peerId = remotePeerId;
            link.pc = pc;
            link.initiatedByUs = initiateOffer;
        }

        if (initiateOffer) {
            try {
                auto ctrlDc = pc->createDataChannel("collab-ctrl");
                rtc::DataChannelInit liveInit;
                liveInit.reliability.unordered = true;
                liveInit.reliability.maxRetransmits = 0;
                auto liveDc = pc->createDataChannel("collab-live", liveInit);
                bindDataChannel(remotePeerId, ctrlDc, false);
                bindDataChannel(remotePeerId, liveDc, true);
            } catch (const std::exception &e) {
                RPC_LOG("Collab createDataChannel error: %s", e.what());
            }
        }
        return pc;
    }

    void bindDataChannel(const QString &remotePeerId, std::shared_ptr<rtc::DataChannel> dc, bool isLive)
    {
        if (!dc) return;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            PeerLink &link = m_peers[remotePeerId];
            if (isLive) link.live = dc;
            else link.ctrl = dc;
        }

        dc->onOpen([this, remotePeerId, isLive]() {
            bool shouldSendHello = false;
            bool shouldSendSnapshot = false;
            {
                std::lock_guard<std::mutex> lock(m_netMutex);
                auto it = m_peers.find(remotePeerId);
                if (it != m_peers.end()) {
                    if (isLive) it->liveOpen = true;
                    else {
                        it->ctrlOpen = true;
                        shouldSendHello = true;
                        if (m_isHost && !it->snapshotSent) {
                            it->snapshotSent = true;
                            shouldSendSnapshot = true;
                        }
                    }
                }
            }
            if (shouldSendHello) {
                QByteArray hello = packMsgHeader(MsgHello);
                QDataStream ds(&hello, QIODevice::Append);
                ds.setByteOrder(QDataStream::LittleEndian);
                writeUtf8(ds, m_selfPeerId);
                writeUtf8(ds, m_selfName);
                ds << kCollabProtoVersion;
                sendBytesToPeer(remotePeerId, hello, false);
            }
            if (shouldSendSnapshot) {
                // 放到 inbound 队列由引擎线程调用 saveRevp 打包发送 (不能在 rtc 回调线程调 KisImage)
                QByteArray internalReq = packMsgHeader(MsgHello);
                QDataStream ds(&internalReq, QIODevice::Append);
                ds.setByteOrder(QDataStream::LittleEndian);
                writeUtf8(ds, remotePeerId);
                writeUtf8(ds, QStringLiteral("__SEND_SNAPSHOT__"));
                ds << quint32(0);
                std::lock_guard<std::mutex> inLock(m_inMutex);
                m_inboundQueue.append(InboundPacket{remotePeerId, internalReq, false});
                pushUiEvent(QJsonObject{{"type", QStringLiteral("inbound_ready")}});
            }
        });

        dc->onMessage([this, remotePeerId, isLive](rtc::message_variant data) {
            if (!m_active) return;
            if (!std::holds_alternative<rtc::binary>(data)) return;
            const rtc::binary &bin = std::get<rtc::binary>(data);
            if (bin.empty()) return;

            QByteArray raw(reinterpret_cast<const char *>(bin.data()), int(bin.size()));
            // 高频光标直接在网络回调更新最新坐标, 不堆积进引擎主通道
            if (isLive && quint8(raw.at(0)) == MsgCursor) {
                handleCursorFast(raw);
                return;
            }
            {
                std::lock_guard<std::mutex> lock(m_inMutex);
                m_inboundQueue.append(InboundPacket{remotePeerId, std::move(raw), isLive});
            }
            pushUiEvent(QJsonObject{{"type", QStringLiteral("inbound_ready")}});
        });
    }

    void sendBytesToPeer(const QString &peerId, const QByteArray &bytes, bool isLive)
    {
        if (bytes.isEmpty()) return;
        std::shared_ptr<rtc::DataChannel> dc;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto it = m_peers.constFind(peerId);
            if (it != m_peers.constEnd()) {
                dc = (isLive && it->liveOpen) ? it->live : (it->ctrlOpen ? it->ctrl : nullptr);
            }
        }
        if (dc && dc->isOpen()) {
            try {
                dc->send(reinterpret_cast<const rtc::byte *>(bytes.constData()), size_t(bytes.size()));
            } catch (...) {
            }
        }
    }

    void broadcastBytes(const QByteArray &bytes, bool isLive, const QString &excludePeerId = QString())
    {
        if (bytes.isEmpty()) return;
        QVector<std::shared_ptr<rtc::DataChannel>> targets;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            for (auto it = m_peers.cbegin(); it != m_peers.cend(); ++it) {
                if (!excludePeerId.isEmpty() && it.key() == excludePeerId) continue;
                // 若处于树形转发拓扑, 房主跳过由副节点代发的子节点
                if (m_isHost && it->isRelayChild) continue;
                auto dc = (isLive && it->liveOpen) ? it->live : (it->ctrlOpen ? it->ctrl : nullptr);
                if (dc && dc->isOpen()) targets.append(dc);
            }
        }
        for (const auto &dc : targets) {
            try {
                dc->send(reinterpret_cast<const rtc::byte *>(bytes.constData()), size_t(bytes.size()));
            } catch (...) {
            }
        }
    }

    void sendLivePacket(const QByteArray &bytes)
    {
        if (m_isHost) {
            broadcastBytes(bytes, true);
        } else {
            sendBytesToPeer(m_hostPeerId, bytes, true);
        }
    }

    void emitCtrlAction(const QByteArray &innerPkt, bool orderedWithHostSeq)
    {
        if (m_isHost) {
            if (orderedWithHostSeq) {
                const quint64 seq = ++m_hostGlobalSeq;
                m_lastAppliedSeq = seq;
                const QByteArray wrapped = wrapWithSeq(seq, innerPkt);
                rememberHostSeq(seq, wrapped);
                broadcastBytes(wrapped, false);
            } else {
                broadcastBytes(innerPkt, false);
            }
        } else {
            sendBytesToPeer(m_hostPeerId, innerPkt, false);
        }
    }

    void rememberHostSeq(quint64 seq, const QByteArray &wrapped)
    {
        m_seqHistory.append(qMakePair(seq, wrapped));
        if (m_seqHistory.size() > kHostSeqHistoryLimit) {
            m_seqHistory.removeFirst();
        }
    }

    void recomputeTopologyIfNeeded()
    {
        // 大房间 (>10 人) 自动将部分节点编入二跳转发队列 (原型先保持最多 10 人直连, >10 时通知节点)
        std::lock_guard<std::mutex> lock(m_netMutex);
        if (m_peers.size() <= kStarMaxPeers) {
            for (auto it = m_peers.begin(); it != m_peers.end(); ++it) {
                it->isRelayChild = false;
                it->relayViaPeerId.clear();
            }
        }
    }

    void handleCursorFast(const QByteArray &raw)
    {
        QDataStream ds(raw);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        quint8 type = 0;
        ds >> type;
        const QString peerId = readUtf8(ds);
        float x = 0, y = 0, sz = 20;
        quint32 col = 0;
        quint8 drawing = 0;
        ds >> x >> y >> sz >> col >> drawing;
        if (ds.status() != QDataStream::Ok || peerId.isEmpty() || peerId == m_selfPeerId) return;

        // 房主代为广播队友光标
        if (m_isHost) {
            broadcastBytes(raw, true, peerId);
        }

        QString peerName = peerId;
        {
            std::lock_guard<std::mutex> nlock(m_netMutex);
            auto it = m_peers.constFind(peerId);
            if (it != m_peers.constEnd() && !it->name.isEmpty()) peerName = it->name;
        }
        std::lock_guard<std::mutex> lock(m_cursorMutex);
        CursorEntry &c = m_cursors[peerId];
        c.peerId = peerId;
        c.name = peerName;
        c.docX = x;
        c.docY = y;
        c.brushSize = sz;
        c.colorArgb = col;
        c.isDrawing = (drawing != 0);
        c.updatedAtMs = QDateTime::currentMSecsSinceEpoch();
    }

    bool handleInboundPacket(const InboundPacket &pkt)
    {
        if (pkt.data.isEmpty()) return false;
        QDataStream ds(pkt.data);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        quint8 type = 0;
        ds >> type;

        if (type == MsgHello) {
            const QString id = readUtf8(ds);
            const QString nameOrCmd = readUtf8(ds);
            if (nameOrCmd == QLatin1String("__SEND_SNAPSHOT__") && m_isHost) {
                sendDocumentSnapshotToPeer(pkt.fromPeerId);
            } else if (!id.isEmpty()) {
                std::lock_guard<std::mutex> lock(m_netMutex);
                if (m_peers.contains(id) && !nameOrCmd.isEmpty()) {
                    m_peers[id].name = nameOrCmd;
                }
            }
            return false;
        }

        if (type == MsgSeqWrap) {
            quint64 seq = 0;
            QByteArray inner;
            ds >> seq >> inner;
            if (ds.status() != QDataStream::Ok || inner.isEmpty()) return false;
            if (seq <= m_lastAppliedSeq) return false; // 幂等防重
            m_lastAppliedSeq = seq;
            return dispatchInnerPacket(pkt.fromPeerId, inner, false);
        }

        if (type == MsgSnapshotBegin) {
            quint64 docSeq = 0;
            quint32 totalBytes = 0, totalChunks = 0;
            QByteArray sha256;
            ds >> docSeq >> totalBytes >> totalChunks >> sha256;
            if (ds.status() != QDataStream::Ok || totalChunks == 0 || totalChunks > 32768) return false;
            m_incomingSnapshot = IncomingSnapshotAssembly{};
            m_incomingSnapshot.docSeq = docSeq;
            m_incomingSnapshot.totalBytes = totalBytes;
            m_incomingSnapshot.totalChunks = totalChunks;
            m_incomingSnapshot.expectedSha256 = sha256;
            m_incomingSnapshot.chunks.resize(int(totalChunks));
            m_incomingSnapshot.active = true;
            pushUiEvent(QJsonObject{
                {"type", QStringLiteral("snapshot_progress")},
                {"received", 0},
                {"total", int(totalChunks)},
            });
            return false;
        }

        if (type == MsgSnapshotChunk) {
            quint32 idx = 0;
            QByteArray chunk;
            ds >> idx >> chunk;
            if (!m_incomingSnapshot.active || idx >= m_incomingSnapshot.totalChunks) return false;
            if (m_incomingSnapshot.chunks[int(idx)].isEmpty()) {
                m_incomingSnapshot.chunks[int(idx)] = chunk;
                m_incomingSnapshot.receivedChunks++;
            }
            pushUiEvent(QJsonObject{
                {"type", QStringLiteral("snapshot_progress")},
                {"received", int(m_incomingSnapshot.receivedChunks)},
                {"total", int(m_incomingSnapshot.totalChunks)},
            });
            if (m_incomingSnapshot.receivedChunks == m_incomingSnapshot.totalChunks) {
                return finalizeIncomingSnapshot();
            }
            return false;
        }

        if (type == MsgBrushReq) {
            const QString reqPeer = readUtf8(ds);
            const QString md5 = readUtf8(ds);
            const QByteArray bytes = m_presetBytesCache.value(md5);
            if (!bytes.isEmpty() && !reqPeer.isEmpty()) {
                sendBrushChunks(reqPeer, md5, bytes);
            }
            return false;
        }

        if (type == MsgBrushChunk) {
            const QString md5 = readUtf8(ds);
            quint32 totalBytes = 0, totalChunks = 0, idx = 0;
            QByteArray chunk;
            ds >> totalBytes >> totalChunks >> idx >> chunk;
            if (md5.isEmpty() || totalChunks == 0 || idx >= totalChunks) return false;
            IncomingBrushAssembly &ab = m_incomingBrushes[md5];
            if (ab.chunks.size() != int(totalChunks)) {
                ab.presetMd5 = md5;
                ab.totalBytes = totalBytes;
                ab.totalChunks = totalChunks;
                ab.chunks.resize(int(totalChunks));
                ab.receivedChunks = 0;
            }
            if (ab.chunks[int(idx)].isEmpty()) {
                ab.chunks[int(idx)] = chunk;
                ab.receivedChunks++;
            }
            if (ab.receivedChunks == ab.totalChunks) {
                return finalizeIncomingBrush(md5);
            }
            return false;
        }

        // 若是房主收到普通成员发来的非包装包: 先定序广播再本地执行
        if (m_isHost && type != MsgStrokeDelta && type != MsgStrokeCancel) {
            const quint64 seq = ++m_hostGlobalSeq;
            const QByteArray wrapped = wrapWithSeq(seq, pkt.data);
            rememberHostSeq(seq, wrapped);
            broadcastBytes(wrapped, false, pkt.fromPeerId);
            // 也发回给发件人作为全局顺序确认
            sendBytesToPeer(pkt.fromPeerId, wrapped, false);
        } else if (m_isHost && (type == MsgStrokeDelta || type == MsgStrokeCancel)) {
            broadcastBytes(pkt.data, pkt.isLiveChannel, pkt.fromPeerId);
        }

        return dispatchInnerPacket(pkt.fromPeerId, pkt.data, pkt.isLiveChannel);
    }

    bool dispatchInnerPacket(const QString &fromPeerId, const QByteArray &raw, bool /*isLive*/)
    {
        if (raw.isEmpty()) return false;
        QDataStream ds(raw);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        quint8 type = 0;
        ds >> type;

        if (type == MsgStrokeBegin) {
            quint32 strokeId = 0, seed = 0, colorArgb = 0;
            float size = 20, opacity = 1, x = 0, y = 0, p = 1, tx = 0, ty = 0, rot = 0;
            quint8 toolMode = 0;
            const QString pId = readUtf8(ds);
            ds >> strokeId;
            const QString lUuid = readUtf8(ds);
            const QString pMd5 = readUtf8(ds);
            ds >> seed >> colorArgb >> size >> opacity >> toolMode >> x >> y >> p >> tx >> ty >> rot;
            if (ds.status() != QDataStream::Ok || pId == m_selfPeerId) return false;
            return handleStrokeBegin(pId, strokeId, lUuid, pMd5, seed, colorArgb, size, opacity, toolMode, x, y, p, tx, ty, rot);
        }

        if (type == MsgStrokeDelta) {
            const QString pId = readUtf8(ds);
            quint32 strokeId = 0, seqStart = 0;
            quint16 count = 0;
            ds >> strokeId >> seqStart >> count;
            if (ds.status() != QDataStream::Ok || pId == m_selfPeerId) return false;
            QVector<CollabSample> samples;
            samples.reserve(count);
            for (quint16 i = 0; i < count; ++i) {
                CollabSample s;
                ds >> s.x >> s.y >> s.pressure >> s.tiltX >> s.tiltY >> s.rotation >> s.timeSec;
                if (ds.status() != QDataStream::Ok) break;
                samples.append(s);
            }
            return handleStrokeSamples(pId, strokeId, seqStart, samples);
        }

        if (type == MsgStrokeCommit) {
            const QString pId = readUtf8(ds);
            quint32 strokeId = 0, total = 0;
            ds >> strokeId >> total;
            if (ds.status() != QDataStream::Ok || pId == m_selfPeerId) return false;
            QVector<CollabSample> samples;
            samples.reserve(int(qMin<quint32>(total, 32768u)));
            for (quint32 i = 0; i < total; ++i) {
                CollabSample s;
                ds >> s.x >> s.y >> s.pressure >> s.tiltX >> s.tiltY >> s.rotation >> s.timeSec;
                if (ds.status() != QDataStream::Ok) break;
                samples.append(s);
            }
            return handleStrokeCommit(pId, strokeId, samples);
        }

        if (type == MsgStrokeCancel) {
            const QString pId = readUtf8(ds);
            quint32 strokeId = 0;
            ds >> strokeId;
            if (pId == m_selfPeerId) return false;
            return cancelOneRemoteStroke(pId + QLatin1Char(':') + QString::number(strokeId));
        }

        if (type == MsgLayerOp) {
            const QString actorPeerId = readUtf8(ds);
            quint8 opKind = 0;
            ds >> opKind;
            const QString layerUuid = readUtf8(ds);
            const QString extraStr = readUtf8(ds);
            double extraVal = 0.0;
            qint32 extraInt = 0;
            ds >> extraVal >> extraInt;
            const QString ownerId = readUtf8(ds);
            quint8 perm = 0;
            ds >> perm;
            const QString aboveUuid = readUtf8(ds);
            if (ds.status() != QDataStream::Ok || actorPeerId == m_selfPeerId) return false;
            return applyRemoteLayerOp(opKind, layerUuid, extraStr, extraVal, extraInt, ownerId, perm, aboveUuid);
        }

        if (type == MsgTilePatch) {
            const QString actorPeerId = readUtf8(ds);
            const QString layerUuid = readUtf8(ds);
            qint32 x = 0, y = 0, w = 0, h = 0;
            quint8 codec = 0;
            QByteArray compressed;
            ds >> x >> y >> w >> h >> codec >> compressed;
            if (ds.status() != QDataStream::Ok || actorPeerId == m_selfPeerId) return false;
            return applyCompressedPatchToLayer(layerUuid, QRect(x, y, w, h), compressed, false);
        }

        return false;
    }

    void sendDocumentSnapshotToPeer(const QString &peerId)
    {
        if (!m_core->m_document || m_snapshotTempPath.isEmpty()) return;
        if (!m_core->saveRevp(m_snapshotTempPath)) {
            RPC_LOG("Collab saveRevp failed for snapshot");
            return;
        }
        QFile f(m_snapshotTempPath);
        if (!f.open(QIODevice::ReadOnly)) return;
        const QByteArray bytes = f.readAll();
        f.close();
        f.remove();
        if (bytes.isEmpty()) return;

        const QByteArray sha = QCryptographicHash::hash(bytes, QCryptographicHash::Sha256);
        const quint32 totalBytes = quint32(bytes.size());
        const quint32 totalChunks = quint32((bytes.size() + kSnapshotChunkBytes - 1) / kSnapshotChunkBytes);

        QByteArray beginPkt = packMsgHeader(MsgSnapshotBegin);
        QDataStream bds(&beginPkt, QIODevice::Append);
        bds.setByteOrder(QDataStream::LittleEndian);
        bds << m_hostGlobalSeq << totalBytes << totalChunks << sha;
        sendBytesToPeer(peerId, beginPkt, false);

        for (quint32 i = 0; i < totalChunks; ++i) {
            const int offset = int(i) * kSnapshotChunkBytes;
            const int len = qMin(kSnapshotChunkBytes, bytes.size() - offset);
            QByteArray cPkt = packMsgHeader(MsgSnapshotChunk);
            QDataStream cds(&cPkt, QIODevice::Append);
            cds.setByteOrder(QDataStream::LittleEndian);
            cds << i << bytes.mid(offset, len);
            sendBytesToPeer(peerId, cPkt, false);
        }
    }

    bool finalizeIncomingSnapshot()
    {
        QByteArray full;
        full.reserve(int(m_incomingSnapshot.totalBytes));
        for (const QByteArray &c : m_incomingSnapshot.chunks) {
            full.append(c);
        }
        const quint64 docSeq = m_incomingSnapshot.docSeq;
        const QByteArray expectedSha = m_incomingSnapshot.expectedSha256;
        m_incomingSnapshot = IncomingSnapshotAssembly{};

        if (QCryptographicHash::hash(full, QCryptographicHash::Sha256) != expectedSha) {
            pushUiEvent(QJsonObject{{"type", QStringLiteral("snapshot_error")}, {"reason", QStringLiteral("checksum")}});
            return false;
        }

        QFile f(m_snapshotTempPath);
        if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) return false;
        f.write(full);
        f.close();

        const bool prevUndo = m_core->m_undoCaptureEnabled;
        m_core->m_undoCaptureEnabled = false;
        const bool loaded = m_core->loadRevp(m_snapshotTempPath);
        m_core->m_undoCaptureEnabled = prevUndo;
        f.remove();

        if (!loaded) {
            pushUiEvent(QJsonObject{{"type", QStringLiteral("snapshot_error")}, {"reason", QStringLiteral("load_revp")}});
            return false;
        }

        m_lastAppliedSeq = docSeq;
        m_core->m_undoCaptureEnabled = false;
        pushUiEvent(QJsonObject{
            {"type", QStringLiteral("doc_reloaded")},
            {"width", m_core->docWidth()},
            {"height", m_core->docHeight()},
            {"docSeq", qint64(docSeq)},
        });
        pushUiEvent(QJsonObject{{"type", QStringLiteral("layers_changed")}});
        return true;
    }

    void serializeCurrentPreset(QString &outMd5, QByteArray &outBytes)
    {
        if (!m_core->m_brushPreset) return;
        QBuffer buf(&outBytes);
        if (buf.open(QIODevice::WriteOnly)) {
            m_core->m_brushPreset->saveToDevice(&buf);
            buf.close();
        }
        if (outBytes.isEmpty()) return;
        outMd5 = QString::fromLatin1(QCryptographicHash::hash(outBytes, QCryptographicHash::Md5).toHex());
        if (!m_presetBytesCache.contains(outMd5)) {
            m_presetBytesCache.insert(outMd5, outBytes);
        }
        if (!m_presetCache.contains(outMd5)) {
            m_presetCache.insert(outMd5, m_core->m_brushPreset);
        }
    }

    void sendBrushChunks(const QString &targetPeerId, const QString &md5, const QByteArray &bytes)
    {
        const quint32 totalBytes = quint32(bytes.size());
        const quint32 totalChunks = quint32((bytes.size() + kBrushChunkBytes - 1) / kBrushChunkBytes);
        for (quint32 i = 0; i < totalChunks; ++i) {
            const int offset = int(i) * kBrushChunkBytes;
            const int len = qMin(kBrushChunkBytes, bytes.size() - offset);
            QByteArray pkt = packMsgHeader(MsgBrushChunk);
            QDataStream ds(&pkt, QIODevice::Append);
            ds.setByteOrder(QDataStream::LittleEndian);
            writeUtf8(ds, md5);
            ds << totalBytes << totalChunks << i << bytes.mid(offset, len);
            sendBytesToPeer(targetPeerId, pkt, false);
        }
    }

    bool finalizeIncomingBrush(const QString &md5)
    {
        IncomingBrushAssembly ab = m_incomingBrushes.take(md5);
        QByteArray full;
        full.reserve(int(ab.totalBytes));
        for (const QByteArray &c : ab.chunks) full.append(c);
        if (full.isEmpty()) return false;

        const QString path = m_brushCacheDir + QLatin1Char('/') + md5 + QStringLiteral(".kpp");
        QFile f(path);
        if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            f.write(full);
            f.close();
        }
        KisPaintOpPresetSP preset = m_core->loadPresetObjectFromPath(path, md5);
        if (!preset) return false;

        m_presetCache.insert(md5, preset);
        m_presetBytesCache.insert(md5, full);

        bool dirty = false;
        QVector<QString> readyToCommit;
        for (auto it = m_remoteStrokes.begin(); it != m_remoteStrokes.end(); ++it) {
            RemoteStrokeSession *s = it.value();
            if (!s || s->presetMd5 != md5 || s->preset) continue;
            if (initRemoteStrokePainter(s, preset)) {
                QVector<CollabSample> pending = s->pendingSamples;
                s->pendingSamples.clear();
                if (!pending.isEmpty()) {
                    paintRemoteSessionSamples(s, pending);
                    dirty = true;
                }
                if (s->committed) {
                    readyToCommit.append(it.key());
                }
            }
        }
        for (const QString &k : readyToCommit) {
            if (commitRemoteSession(k)) dirty = true;
        }
        return dirty;
    }

    bool handleStrokeBegin(const QString &peerId, quint32 strokeId, const QString &layerUuid,
                           const QString &presetMd5, quint32 seed, quint32 colorArgb,
                           float brushSize, float opacity, int toolMode,
                           float x, float y, float p, float tx, float ty, float rot)
    {
        const QString key = peerId + QLatin1Char(':') + QString::number(strokeId);
        if (m_remoteStrokes.contains(key)) {
            delete m_remoteStrokes.take(key);
        }
        auto *s = new RemoteStrokeSession();
        s->key = key;
        s->peerId = peerId;
        s->strokeId = strokeId;
        s->layerUuid = layerUuid;
        s->presetMd5 = presetMd5;
        s->seed = seed;
        s->color = QColor::fromRgba(colorArgb);
        s->brushSize = qBound<qreal>(0.5, qreal(brushSize), 1500.0);
        s->opacity = qBound<qreal>(0.0, qreal(opacity), 1.0);
        s->toolMode = toolMode;
        s->lastPos = QPointF(x, y);
        s->lastTimeSec = 0.0;

        CollabSample s0{x, y, p, tx, ty, rot, 0.0f};
        s->allSamples.append(s0);

        m_remoteStrokes.insert(key, s);

        KisPaintOpPresetSP preset = m_presetCache.value(presetMd5);
        if (!preset && !presetMd5.isEmpty()) {
            const QString cachedPath = m_brushCacheDir + QLatin1Char('/') + presetMd5 + QStringLiteral(".kpp");
            if (QFile::exists(cachedPath)) {
                preset = m_core->loadPresetObjectFromPath(cachedPath, presetMd5);
                if (preset) m_presetCache.insert(presetMd5, preset);
            }
        }
        if (!preset) {
            if (!presetMd5.isEmpty()) {
                QByteArray req = packMsgHeader(MsgBrushReq);
                QDataStream rds(&req, QIODevice::Append);
                rds.setByteOrder(QDataStream::LittleEndian);
                writeUtf8(rds, m_selfPeerId);
                writeUtf8(rds, presetMd5);
                sendBytesToPeer(m_isHost ? peerId : m_hostPeerId, req, false);
                s->pendingSamples.append(s0);
                return false;
            }
            preset = m_core->m_brushPreset;
        }
        if (preset && initRemoteStrokePainter(s, preset)) {
            return paintRemoteSessionSamples(s, {s0});
        }
        return false;
    }

    bool initRemoteStrokePainter(RemoteStrokeSession *s, const KisPaintOpPresetSP &srcPreset)
    {
        if (!s || !srcPreset || !m_core->m_document) return false;
        const int layerIdx = m_core->layerIndexByUuid(s->layerUuid);
        if (layerIdx < 0 || layerIdx >= m_core->m_layers.size()) return false;
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[layerIdx].node);
        if (!pl || !pl->paintDevice()) return false;

        // 克隆一份独立 preset 防止远端改写 size/opacity 污染本地或其它并发笔画
        KisPaintOpPresetSP cloned = srcPreset->clone().dynamicCast<KisPaintOpPreset>();
        s->preset = cloned ? cloned : srcPreset;

        const bool isEraser = (s->toolMode == ReverieCore::ToolEraser) ||
                              (s->preset->settings() && s->preset->settings()->eraserMode());
        const QString pid = s->preset->paintOp().id();
        const bool isSmudge = (s->toolMode == ReverieCore::ToolSmudge) || (pid == QLatin1String("colorsmudge"));
        const bool readsCanvas = isSmudge || pid == QLatin1String("deformbrush") ||
                                 pid == QLatin1String("filter") || pid == QLatin1String("duplicate");

        if (s->preset->settings()) {
            s->preset->settings()->setPaintOpSize(s->brushSize);
            s->preset->settings()->setPaintOpOpacity(s->opacity);
            s->preset->settings()->setEraserMode(isEraser);
        }

        s->isFullDeviceCopy = readsCanvas;
        if (readsCanvas) {
            // COW 浅拷贝当前图层像素供涂抹/液化笔刷采样
            s->previewDev = new KisPaintDevice(*pl->paintDevice());
        } else {
            s->previewDev = pl->paintDevice()->createCompositionSourceDevice();
        }
        s->previewCompositeOp = isEraser ? QStringLiteral("erase") : QStringLiteral("normal");

        const KoColorSpace *cs = m_core->m_document->colorSpace();
        s->painter = new KisPainter(s->previewDev);
        s->painter->setFillStyle(KisPainter::FillStyleForegroundColor);
        s->painter->setStrokeStyle(KisPainter::StrokeStyleBrush);
        s->painter->setCompositeOpId(readsCanvas && isEraser ? QStringLiteral("erase") : QStringLiteral("alphadarken"));
        s->painter->setOpacityToUnit();
        s->painter->setPaintColor(KoColor(s->color, cs));
        s->painter->setBackgroundColor(KoColor(Qt::white, cs));
        s->painter->setRunnableStrokeJobsInterface(&m_core->m_fakeExecutor);

        s->randomSource = new KisRandomSource(int(s->seed));
        s->perStrokeRandom = new KisPerStrokeRandomSource();
        s->op = KisPaintOpRegistry::instance()->paintOp(s->preset, s->painter, KisNodeSP(pl), m_core->m_document);
        if (!s->op) {
            s->op = new KisBrushOp(s->preset->settings(), s->painter, KisNodeSP(pl), m_core->m_document);
        }
        s->distance = new KisDistanceInformation(s->lastPos, 0.0);
        return s->op != nullptr;
    }

    bool handleStrokeSamples(const QString &peerId, quint32 strokeId, quint32 seqStart, const QVector<CollabSample> &samples)
    {
        const QString key = peerId + QLatin1Char(':') + QString::number(strokeId);
        RemoteStrokeSession *s = m_remoteStrokes.value(key, nullptr);
        if (!s || samples.isEmpty()) return false;

        // 按 seqStart 追加新样本 (忽略与已收样本重叠部分)
        QVector<CollabSample> fresh;
        for (int i = 0; i < samples.size(); ++i) {
            const quint32 sampleIdx = seqStart + quint32(i);
            if (int(sampleIdx) < s->allSamples.size()) continue;
            s->allSamples.append(samples[i]);
            fresh.append(samples[i]);
        }
        if (fresh.isEmpty()) return false;

        if (!s->op) {
            s->pendingSamples.append(fresh);
            return false;
        }
        return paintRemoteSessionSamples(s, fresh);
    }

    bool paintRemoteSessionSamples(RemoteStrokeSession *s, const QVector<CollabSample> &samples)
    {
        if (!s || !s->op || !s->distance || samples.isEmpty() || !m_core->m_document) return false;
        QRect segBounds;
        for (const CollabSample &cs : samples) {
            const QPointF cur(cs.x, cs.y);
            const StrokePaintSample curSample{cur, cs.pressure, cs.tiltX, cs.tiltY, cs.rotation, cs.timeSec};
            if (!s->hasStartedPaint) {
                const KisPaintInformation info = makePaintInfo(curSample, 0.0, s->randomSource, s->perStrokeRandom);
                s->op->paintAt(info, s->distance);
                s->hasStartedPaint = true;
                s->lastPos = cur;
                s->lastTimeSec = cs.timeSec;
                segBounds = QRect(int(cur.x()), int(cur.y()), 1, 1);
            } else {
                const qreal dist = std::hypot(cur.x() - s->lastPos.x(), cur.y() - s->lastPos.y());
                if (dist < 1e-3) continue;
                const StrokePaintSample prevSample{s->lastPos, cs.pressure, cs.tiltX, cs.tiltY, cs.rotation, s->lastTimeSec};
                const KisPaintInformation a = makePaintInfo(prevSample, 0.0, s->randomSource, s->perStrokeRandom);
                const KisPaintInformation b = makePaintInfo(curSample, 0.0, s->randomSource, s->perStrokeRandom);
                s->op->paintLine(a, b, s->distance);
                s->lastPos = cur;
                s->lastTimeSec = cs.timeSec;
                const QRect ptR = QRect(a.pos().toPoint(), b.pos().toPoint()).normalized();
                segBounds = segBounds.isNull() ? ptR : segBounds.united(ptR);
            }
            m_core->m_fakeExecutor.flushPendingJobs(s->brushSize);
            while (true) {
                QVector<KisRunnableStrokeJobData *> jobs;
                auto res = s->op->doAsynchronousUpdate(jobs);
                reverieRunStrokeJobsBatch(jobs, s->brushSize);
                if (jobs.isEmpty() || !res.second) break;
            }
        }

        if (segBounds.isNull()) return false;
        const int margin = qMax(int(s->brushSize * 1.5), 24) + 16;
        const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
        const QRect dirty = segBounds.adjusted(-margin, -margin, margin, margin).intersected(docRect);
        if (dirty.isEmpty()) return false;
        s->dirtyBounds = s->dirtyBounds.isNull() ? dirty : s->dirtyBounds.united(dirty);
        m_core->invalidateStaticUnderlayRect(dirty);
        m_core->markStrokeDirty(dirty);
        return true;
    }

    bool handleStrokeCommit(const QString &peerId, quint32 strokeId, const QVector<CollabSample> &allSamples)
    {
        const QString key = peerId + QLatin1Char(':') + QString::number(strokeId);
        RemoteStrokeSession *s = m_remoteStrokes.value(key, nullptr);
        if (!s) return false;

        // 若不可靠流有丢包, 从 commit 带的完整样本补画缺失尾段
        if (allSamples.size() > s->allSamples.size()) {
            const QVector<CollabSample> missing = allSamples.mid(s->allSamples.size());
            s->allSamples = allSamples;
            if (!s->op) {
                s->pendingSamples.append(missing);
            } else {
                paintRemoteSessionSamples(s, missing);
            }
        }
        s->committed = true;
        if (!s->op) {
            // 仍在等 .kpp, finalizeIncomingBrush 到来后会立刻补画并提交
            return false;
        }
        return commitRemoteSession(key);
    }

    bool commitRemoteSession(const QString &key)
    {
        RemoteStrokeSession *s = m_remoteStrokes.take(key);
        if (!s) return false;
        bool updated = false;
        const int layerIdx = m_core->layerIndexByUuid(s->layerUuid);
        if (s->previewDev && layerIdx >= 0 && layerIdx < m_core->m_layers.size() && m_core->m_document) {
            if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[layerIdx].node)) {
                if (pl->paintDevice()) {
                    const QRect ext = s->previewDev->exactBounds().intersected(
                        QRect(0, 0, m_core->m_docWidth, m_core->m_docHeight));
                    if (!ext.isEmpty()) {
                        KisPainter gc(pl->paintDevice());
                        gc.setCompositeOpId(s->isFullDeviceCopy ? QString(COMPOSITE_COPY) : s->previewCompositeOp);
                        gc.setOpacityF(s->isFullDeviceCopy ? 1.0 : qBound<qreal>(0.0, s->opacity, 1.0));
                        if (pl->alphaLocked()) {
                            gc.setChannelFlags(pl->channelLockFlags());
                        }
                        gc.bitBlt(ext.topLeft(), s->previewDev, ext);
                        gc.end();
                        m_core->invalidateStaticUnderlayRect(ext);
                        m_core->markStrokeDirty(ext);
                        m_core->bumpLayerThumbGen(pl);
                        updated = true;
                    }
                }
            }
        }
        delete s;
        return updated;
    }

    bool cancelOneRemoteStroke(const QString &key)
    {
        RemoteStrokeSession *s = m_remoteStrokes.take(key);
        if (!s) return false;
        const QRect dirty = s->dirtyBounds;
        delete s;
        if (!dirty.isEmpty()) {
            m_core->invalidateStaticUnderlayRect(dirty);
            m_core->markStrokeDirty(dirty);
            return true;
        }
        return false;
    }

    void cancelRemoteStrokesForPeer(const QString &peerId)
    {
        QVector<QString> keys;
        for (auto it = m_remoteStrokes.cbegin(); it != m_remoteStrokes.cend(); ++it) {
            if (it.value() && it.value()->peerId == peerId) {
                keys.append(it.key());
            }
        }
        for (const QString &k : keys) {
            cancelOneRemoteStroke(k);
        }
    }

    bool applyRemoteLayerOp(quint8 opKind, const QString &layerUuid, const QString &extraStr,
                            double extraVal, int extraInt, const QString &ownerId,
                            quint8 perm, const QString &aboveUuid)
    {
        if (!m_core->m_document) return false;
        SuppressEmitGuard guard(m_core->m_collabSuppressEmit);
        const bool prevUndo = m_core->m_undoCaptureEnabled;
        m_core->m_undoCaptureEnabled = false;

        bool changed = false;
        const int savedCurrent = m_core->m_currentLayer;

        if (opKind == ReverieCore::CollabLayerAddPaint || opKind == ReverieCore::CollabLayerAddGroup) {
            if (m_core->layerIndexByUuid(layerUuid) < 0) {
                const int aboveIdx = m_core->layerIndexByUuid(aboveUuid);
                if (aboveIdx >= 0) m_core->m_currentLayer = aboveIdx;
                const int createdIdx = (opKind == ReverieCore::CollabLayerAddGroup)
                    ? m_core->addGroupLayer(extraStr)
                    : m_core->addLayer(extraStr);
                if (createdIdx >= 0 && createdIdx < m_core->m_layers.size() && m_core->m_layers[createdIdx].node) {
                    std::lock_guard<std::mutex> mirrorLock(m_core->m_layerMirrorMutex);
                    KisNode *n = m_core->m_layers[createdIdx].node;
                    const QString oldUuid = m_core->m_layers[createdIdx].uuid;
                    m_core->m_uuidToLayerIndex.remove(oldUuid);
                    m_core->m_layers[createdIdx].uuid = layerUuid;
                    m_core->m_layers[createdIdx].ownerPeerId = ownerId;
                    m_core->m_layers[createdIdx].collabPermission = perm;
                    m_core->m_uuidToLayerIndex.insert(layerUuid, createdIdx);
                    reverie::setLayerProperty(n, "reverie_uuid", layerUuid);
                    reverie::setLayerProperty(n, "reverie_owner", ownerId);
                    reverie::setLayerProperty(n, "reverie_collab_perm", int(perm));
                    changed = true;
                }
            }
        } else {
            const int idx = m_core->layerIndexByUuid(layerUuid);
            if (idx >= 0 && idx < m_core->m_layers.size()) {
                switch (opKind) {
                case ReverieCore::CollabLayerRemove:
                    m_core->removeLayer(idx);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerRename:
                    m_core->setLayerName(idx, extraStr);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerVisible:
                    m_core->setLayerVisible(idx, extraInt != 0);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerLocked:
                    m_core->setLayerLocked(idx, extraInt != 0);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerAlphaLocked:
                    m_core->setLayerAlphaLocked(idx, extraInt != 0);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerOpacity:
                    m_core->setLayerOpacity(idx, extraVal);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerBlend:
                    m_core->setLayerBlendMode(idx, extraStr);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerOwnerPerm:
                    m_core->setLayerOwnerPeerId(idx, extraStr);
                    m_core->setLayerCollabPermission(idx, extraInt);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerClear:
                    m_core->clearLayer(idx);
                    changed = true;
                    break;
                default:
                    break;
                }
            }
        }

        if (savedCurrent >= 0 && savedCurrent < m_core->m_layers.size() &&
            opKind != ReverieCore::CollabLayerRemove) {
            m_core->m_currentLayer = savedCurrent;
        }
        m_core->m_undoCaptureEnabled = prevUndo;
        if (changed) {
            pushUiEvent(QJsonObject{{"type", QStringLiteral("layers_changed")}});
        }
        return changed;
    }

    void pushPixelUndo(const QString &layerUuid, const QRect &rect,
                       const KisPaintDeviceSP &beforeDev, const KisPaintDeviceSP &afterDev)
    {
        if (!beforeDev || !afterDev || rect.isEmpty()) return;
        const int pxSize = int(afterDev->pixelSize());
        const int byteCount = rect.width() * rect.height() * pxSize;
        if (byteCount <= 0 || byteCount > 64 * 1024 * 1024) return;

        QByteArray rawBefore(byteCount, 0);
        QByteArray rawAfter(byteCount, 0);
        beforeDev->readBytes(reinterpret_cast<quint8 *>(rawBefore.data()), rect.x(), rect.y(), rect.width(), rect.height());
        afterDev->readBytes(reinterpret_cast<quint8 *>(rawAfter.data()), rect.x(), rect.y(), rect.width(), rect.height());

        CollabUndoEntry entry;
        entry.layerUuid = layerUuid;
        entry.rect = rect;
        entry.beforeCompressed = qCompress(rawBefore, 4);
        entry.afterCompressed = qCompress(rawAfter, 4);

        if (m_undoIdx < m_undoEntries.size()) {
            m_undoEntries.resize(m_undoIdx);
        }
        m_undoEntries.append(std::move(entry));
        if (m_undoEntries.size() > kUndoCap) {
            m_undoEntries.removeFirst();
        }
        m_undoIdx = m_undoEntries.size();
    }

    bool applyCompressedPatchToLayer(const QString &layerUuid, const QRect &rect,
                                     const QByteArray &compressed, bool broadcastPatch)
    {
        if (!m_core->m_document || rect.isEmpty() || compressed.isEmpty()) return false;
        const int idx = m_core->layerIndexByUuid(layerUuid);
        if (idx < 0 || idx >= m_core->m_layers.size()) return false;
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[idx].node);
        if (!pl || !pl->paintDevice()) return false;

        const QByteArray raw = qUncompress(compressed);
        const int expected = rect.width() * rect.height() * int(pl->paintDevice()->pixelSize());
        if (raw.size() != expected) return false;

        pl->paintDevice()->writeBytes(reinterpret_cast<const quint8 *>(raw.constData()),
                                      rect.x(), rect.y(), rect.width(), rect.height());
        m_core->invalidateStaticUnderlayRect(rect);
        m_core->markStrokeDirty(rect);
        m_core->bumpLayerThumbGen(pl);

        if (broadcastPatch) {
            QByteArray pkt = packMsgHeader(MsgTilePatch);
            QDataStream ds(&pkt, QIODevice::Append);
            ds.setByteOrder(QDataStream::LittleEndian);
            writeUtf8(ds, m_selfPeerId);
            writeUtf8(ds, layerUuid);
            ds << qint32(rect.x()) << qint32(rect.y()) << qint32(rect.width()) << qint32(rect.height())
               << quint8(1) << compressed;
            emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
        }
        return true;
    }
};

} // namespace reverie

// ==================== ReverieCore 公开协同接口转发 ====================

bool ReverieCore::collabStartSession(const QString &roomCode,
                                     const QString &selfPeerId,
                                     const QString &selfName,
                                     const QString &hostPeerId,
                                     const QString &iceServersJson,
                                     const QString &cacheDir)
{
    if (!m_collab) {
        m_collab = new reverie::CollabRuntime(this);
    }
    if (!m_collab->start(roomCode, selfPeerId, selfName, hostPeerId, iceServersJson, cacheDir)) {
        return false;
    }
    m_collabActive = true;
    clearUndoHistory();
    m_undoCaptureEnabled = false;
    // 房主建房时, 为所有已有非背景图层补齐房主所有权 (若原为空)
    if (selfPeerId == hostPeerId) {
        std::lock_guard<std::mutex> mirrorLock(m_layerMirrorMutex);
        for (int i = 1; i < m_layers.size(); ++i) {
            if (m_layers[i].node && m_layers[i].ownerPeerId.isEmpty()) {
                m_layers[i].ownerPeerId = selfPeerId;
                reverie::setLayerProperty(m_layers[i].node, "reverie_owner", selfPeerId);
            }
        }
    }
    return true;
}

void ReverieCore::collabStopSession()
{
    m_collabActive = false;
    m_undoCaptureEnabled = true;
    m_collabPendingSamples.clear();
    if (m_collab) {
        delete m_collab;
        m_collab = nullptr;
    }
}

void ReverieCore::collabResetDocumentState()
{
    m_collabPendingSamples.clear();
    if (m_collab) {
        m_collab->clearDocumentBoundState();
    }
}

bool ReverieCore::collabCanEditCurrentLayer() const
{
    return collabCanEditLayer(m_currentLayer);
}

bool ReverieCore::collabCanEditLayer(int index) const
{
    if (index < 0 || index >= m_layers.size()) return false;
    const LayerEntry &e = m_layers[index];
    if (e.background || e.isGroup || e.locked) return false;
    if (!m_collabActive || !m_collab) return true;
    // permission: 0=Shared, 1=OwnerOnly, 2=Locked
    if (e.collabPermission == 2) return false;
    if (e.collabPermission == 1) {
        return e.ownerPeerId.isEmpty() || e.ownerPeerId == m_collab->selfPeerId();
    }
    return true;
}

void ReverieCore::collabOnPeerJoined(const QString &peerId, const QString &name, bool isHostPeer)
{
    if (m_collab) m_collab->onPeerJoined(peerId, name, isHostPeer);
}

void ReverieCore::collabOnPeerLeft(const QString &peerId)
{
    if (m_collab) m_collab->onPeerLeft(peerId);
}

void ReverieCore::collabOnHostChanged(const QString &newHostPeerId)
{
    if (m_collab) m_collab->onHostChanged(newHostPeerId);
}

void ReverieCore::collabOnRemoteSignal(const QString &fromPeerId, const QString &payloadJson)
{
    if (m_collab) m_collab->onRemoteSignal(fromPeerId, payloadJson);
}

bool ReverieCore::collabDrainInbound()
{
    return m_collab ? m_collab->drainInbound() : false;
}

QString ReverieCore::collabPollEventsJson()
{
    return m_collab ? m_collab->pollEventsJson() : QStringLiteral("[]");
}

QString ReverieCore::collabPollCursorsJson()
{
    return m_collab ? m_collab->pollCursorsJson() : QStringLiteral("[]");
}

QString ReverieCore::collabLayersMetaJson() const
{
    std::lock_guard<std::mutex> mirrorLock(m_layerMirrorMutex);
    QJsonArray arr;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        arr.append(QJsonObject{
            {"index", i},
            {"uuid", e.uuid},
            {"ownerPeerId", e.ownerPeerId},
            {"collabPermission", e.collabPermission},
        });
    }
    return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
}

void ReverieCore::collabSendLocalCursor(float docX, float docY, bool isDrawing)
{
    if (m_collab) m_collab->sendLocalCursor(docX, docY, isDrawing);
}

bool ReverieCore::collabUndo()
{
    return m_collab ? m_collab->undo() : false;
}

bool ReverieCore::collabRedo()
{
    return m_collab ? m_collab->redo() : false;
}

bool ReverieCore::collabCanUndo() const
{
    return m_collab ? m_collab->canUndo() : false;
}

bool ReverieCore::collabCanRedo() const
{
    return m_collab ? m_collab->canRedo() : false;
}

int ReverieCore::collabUndoCount() const
{
    return m_collab ? m_collab->undoCount() : 0;
}

int ReverieCore::collabRedoCount() const
{
    return m_collab ? m_collab->redoCount() : 0;
}

bool ReverieCore::hasRemotePreviewForLayer(const QString &layerUuid) const
{
    return m_collab ? m_collab->hasRemotePreviewForLayer(layerUuid) : false;
}

void ReverieCore::compositeRemotePreviewsForLayer(const QString &layerUuid, KisPaintDeviceSP scratch,
                                                  const QRect &r, const QBitArray &alphaLockFlags)
{
    if (m_collab) {
        m_collab->compositeRemotePreviews(layerUuid, scratch, r, alphaLockFlags);
    }
}

bool ReverieCore::hasActiveRemoteStrokeBelow(int stopIdx) const
{
    return m_collab ? m_collab->hasActiveRemoteStrokeBelow(stopIdx) : false;
}

bool ReverieCore::collabTestSimulateRemoteStroke(const QString &peerId, const QString &layerUuid,
                                                quint32 colorArgb, qreal brushSize,
                                                const QVector<QPointF> &points, bool commit)
{
    return m_collab ? m_collab->simulateRemoteStroke(peerId, layerUuid, colorArgb, brushSize, points, commit) : false;
}

void ReverieCore::collabEmitLayerOp(int opKind, int index, const QString &extraStr,
                                    double extraVal, int extraInt)
{
    if (m_collab) m_collab->emitLayerOp(opKind, index, extraStr, extraVal, extraInt);
}

void ReverieCore::collabOnLocalStrokeBegin(int seed)
{
    if (m_collab) m_collab->onLocalStrokeBegin(seed);
}

void ReverieCore::collabOnLocalStrokeFlushed()
{
    if (m_collab) m_collab->onLocalStrokeFlushed();
}

void ReverieCore::collabOnLocalStrokeEnd(const QRect &dirtyRect)
{
    if (m_collab) m_collab->onLocalStrokeEnd(dirtyRect);
}

void ReverieCore::collabOnLocalStrokeCancel()
{
    if (m_collab) m_collab->onLocalStrokeCancel();
}
