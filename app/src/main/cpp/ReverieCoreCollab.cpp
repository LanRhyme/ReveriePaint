/*
 * ReverieCoreCollab - P2P 茶绘协同引擎 (EasyTier + Framed Socket + 多路 StrokeSession + 独立图层撤销)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCoreInternal.h"
#include "ReverieCoreCollab.h"
#include "ReverieCoreLayerExtras.h"
#include "StrokePaintInfo.h"
#include "PaintOpTraits.h"

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <unistd.h>

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
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>
#include <memory>
#include <thread>
#include <vector>

namespace {

constexpr quint32 kCollabProtoVersion = 1;
constexpr int kSnapshotChunkBytes = 16 * 1024;
constexpr int kBrushChunkBytes = 16 * 1024;
constexpr int kCtrlLargeChunkBytes = 48 * 1024;
constexpr int kStarMaxPeers = 10;
constexpr int kUndoCap = 120;
constexpr int kHistoryCap = 512;
constexpr qint64 kHistoryMaxBytes = 128 * 1024 * 1024;
constexpr int kHostSeqHistoryLimit = 512;

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
    MsgUndoReq = 0x1C,
    MsgUndoPatch = 0x1D,
    MsgLargeChunk = 0x1E,
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

struct EtKeyValuePair {
    const char *key;
    const char *value;
};

class EasyTierApi {
public:
    using FnRunNetworkInstance = int (*)(const char *cfg_str);
    using FnRetainNetworkInstance = int (*)(const char *const *inst_names, size_t length);
    using FnDeleteNetworkInstance = int (*)(const char *const *inst_names, size_t length);
    using FnCollectNetworkInfos = int (*)(EtKeyValuePair *infos, size_t max_length);
    using FnCallJsonRpc = int (*)(const char *service_name, const char *method_name,
                                  const char *domain_name, const char *payload_json,
                                  const char **out_response_json);
    using FnGetErrorMsg = void (*)(const char **out);
    using FnFreeString = void (*)(const char *s);

    FnRunNetworkInstance runNetworkInstance = nullptr;
    FnRetainNetworkInstance retainNetworkInstance = nullptr;
    FnDeleteNetworkInstance deleteNetworkInstance = nullptr;
    FnCollectNetworkInfos collectNetworkInfos = nullptr;
    FnCallJsonRpc callJsonRpc = nullptr;
    FnGetErrorMsg getErrorMsg = nullptr;
    FnFreeString freeString = nullptr;

    static EasyTierApi &instance()
    {
        static EasyTierApi api;
        return api;
    }

    bool ensureLoaded(QString *errOut = nullptr)
    {
        std::lock_guard<std::mutex> lock(m_loadMutex);
        if (m_loaded) return true;
        if (!m_handle) {
            m_handle = dlopen("libeasytier_ffi.so", RTLD_NOW | RTLD_LOCAL);
            if (!m_handle) {
                const char *err = dlerror();
                m_loadError = QString::fromUtf8(err ? err : "dlopen libeasytier_ffi.so failed");
                if (errOut) *errOut = m_loadError;
                return false;
            }
        }
        runNetworkInstance = reinterpret_cast<FnRunNetworkInstance>(dlsym(m_handle, "run_network_instance"));
        retainNetworkInstance = reinterpret_cast<FnRetainNetworkInstance>(dlsym(m_handle, "retain_network_instance"));
        deleteNetworkInstance = reinterpret_cast<FnDeleteNetworkInstance>(dlsym(m_handle, "delete_network_instance"));
        collectNetworkInfos = reinterpret_cast<FnCollectNetworkInfos>(dlsym(m_handle, "collect_network_infos"));
        callJsonRpc = reinterpret_cast<FnCallJsonRpc>(dlsym(m_handle, "call_json_rpc"));
        getErrorMsg = reinterpret_cast<FnGetErrorMsg>(dlsym(m_handle, "get_error_msg"));
        freeString = reinterpret_cast<FnFreeString>(dlsym(m_handle, "free_string"));

        if (!runNetworkInstance || !retainNetworkInstance || !deleteNetworkInstance ||
            !collectNetworkInfos || !getErrorMsg || !freeString) {
            m_loadError = QStringLiteral("libeasytier_ffi.so missing required FFI symbols");
            if (errOut) *errOut = m_loadError;
            return false;
        }
        m_loaded = true;
        m_loadError.clear();
        return true;
    }

    QString lastErrorMessage() const
    {
        if (!getErrorMsg) return m_loadError;
        const char *msg = nullptr;
        getErrorMsg(&msg);
        if (!msg) return m_loadError;
        QString out = QString::fromUtf8(msg);
        if (freeString) freeString(msg);
        return out;
    }

private:
    EasyTierApi() = default;
    std::mutex m_loadMutex;
    void *m_handle = nullptr;
    bool m_loaded = false;
    QString m_loadError;
};

struct EasyTierTransportConfig {
    QString networkName;
    QString networkSecret;
    QString selfVirtualIp;
    QString hostVirtualIp;
    int hostMeshPort = 39500;
    QStringList relayPeers;
    QStringList stunServers;
    QStringList stunServersV6;
    bool enableKcpProxy = true;
    bool latencyFirst = true;
};

struct PeerLink {
    QString peerId;
    QString name;
    QString virtualIp;
    bool isHostPeer = false;
    bool ctrlOpen = false;
    bool liveOpen = false;
    bool snapshotSent = false;
    bool isRelayChild = false;
    QString relayViaPeerId;
    qint64 ctrlOpenedAtMs = 0;
    qint64 lastSnapshotReqAtMs = 0;
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

struct IncomingLargeAssembly {
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

constexpr int kMaxWireFrameBytes = 16 * 1024 * 1024;
constexpr const char *kEasyTierInstanceName = "reverie_collab";

QString tomlEscape(const QString &raw)
{
    QString out;
    out.reserve(raw.size() + 4);
    for (QChar ch : raw) {
        if (ch == QLatin1Char('\\')) out += QLatin1String("\\\\");
        else if (ch == QLatin1Char('"')) out += QLatin1String("\\\"");
        else if (ch == QLatin1Char('\n')) out += QLatin1String("\\n");
        else if (ch == QLatin1Char('\r')) out += QLatin1String("\\r");
        else if (ch == QLatin1Char('\t')) out += QLatin1String("\\t");
        else out += ch;
    }
    return out;
}

QString sanitizeStunHost(const QString &raw)
{
    QString s = raw.trimmed();
    if (s.startsWith(QLatin1String("stun:"), Qt::CaseInsensitive)) {
        s = s.mid(5);
    }
    while (s.startsWith(QLatin1Char('/'))) {
        s = s.mid(1);
    }
    if (s.endsWith(QLatin1String(":3478")) && !s.startsWith(QLatin1Char('['))) {
        s.chop(5);
    }
    return s;
}

EasyTierTransportConfig parseEasyTierConfig(const QString &jsonStr,
                                            const QString &roomCode,
                                            const QString &selfPeerId,
                                            const QString &hostPeerId)
{
    EasyTierTransportConfig cfg;
    const QJsonDocument doc = QJsonDocument::fromJson(jsonStr.toUtf8());
    if (doc.isObject()) {
        const QJsonObject obj = doc.object();
        cfg.networkName = obj.value(QStringLiteral("networkName")).toString();
        cfg.networkSecret = obj.value(QStringLiteral("networkSecret")).toString();
        cfg.selfVirtualIp = obj.value(QStringLiteral("selfVirtualIp")).toString();
        cfg.hostVirtualIp = obj.value(QStringLiteral("hostVirtualIp")).toString();
        cfg.hostMeshPort = obj.value(QStringLiteral("hostMeshPort")).toInt(39500);
        cfg.enableKcpProxy = obj.value(QStringLiteral("enableKcpProxy")).toBool(true);
        cfg.latencyFirst = obj.value(QStringLiteral("latencyFirst")).toBool(true);

        const QJsonArray relays = obj.value(QStringLiteral("relayPeers")).toArray();
        for (const QJsonValue &v : relays) {
            const QString u = v.toString().trimmed();
            if (!u.isEmpty() && !cfg.relayPeers.contains(u)) cfg.relayPeers.append(u);
        }
        const QJsonArray stuns = obj.value(QStringLiteral("stunServers")).toArray();
        for (const QJsonValue &v : stuns) {
            const QString u = sanitizeStunHost(v.toString());
            if (!u.isEmpty() && !cfg.stunServers.contains(u)) cfg.stunServers.append(u);
        }
        const QJsonArray stunsV6 = obj.value(QStringLiteral("stunServersV6")).toArray();
        for (const QJsonValue &v : stunsV6) {
            const QString u = v.toString().trimmed();
            if (!u.isEmpty() && !cfg.stunServersV6.contains(u)) cfg.stunServersV6.append(u);
        }
    }

    auto deriveIp = [](const QString &room, const QString &peer) -> QString {
        auto fnv = [](const QString &s) -> quint32 {
            quint32 h = 2166136261u;
            const QByteArray b = s.toUtf8();
            for (char ch : b) {
                h ^= quint8(ch);
                h *= 16777619u;
            }
            return h;
        };
        const quint32 sb = 10u + (fnv(room) % 240u);
        const quint32 hb = 10u + (fnv(peer) % 240u);
        return QStringLiteral("10.144.%1.%2").arg(sb).arg(hb);
    };

    if (cfg.networkName.isEmpty()) {
        cfg.networkName = QStringLiteral("rvp-") + roomCode;
    }
    if (cfg.networkSecret.isEmpty()) {
        const QByteArray digest = QCryptographicHash::hash(
            (QStringLiteral("reverie-mesh-v1:") + roomCode).toUtf8(),
            QCryptographicHash::Sha256);
        cfg.networkSecret = QString::fromLatin1(digest.toHex().left(24));
    }
    if (cfg.selfVirtualIp.isEmpty() && !selfPeerId.isEmpty()) {
        cfg.selfVirtualIp = deriveIp(roomCode, selfPeerId);
    }
    if (cfg.hostVirtualIp.isEmpty() && !hostPeerId.isEmpty()) {
        cfg.hostVirtualIp = deriveIp(roomCode, hostPeerId);
    }
    if (cfg.hostMeshPort <= 1024 || cfg.hostMeshPort > 65535) {
        quint32 h = 2166136261u;
        const QByteArray b = roomCode.toUtf8();
        for (char ch : b) {
            h ^= quint8(ch);
            h *= 16777619u;
        }
        cfg.hostMeshPort = 39000 + int(h % 10000u);
    }
    if (cfg.stunServers.isEmpty()) {
        cfg.stunServers = {
            QStringLiteral("stun.hitv.com"),
            QStringLiteral("stun.miwifi.com"),
            QStringLiteral("stun.chat.bilibili.com"),
            QStringLiteral("txt:stun.easytier.cn"),
        };
    }
    if (cfg.stunServersV6.isEmpty()) {
        cfg.stunServersV6 = {QStringLiteral("txt:stun-v6.easytier.cn")};
    }
    return cfg;
}

bool setSocketNonBlocking(int fd)
{
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

void configureCollabTcpSocket(int fd)
{
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
    int idle = 5;
    int intvl = 2;
    int cnt = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sizeof(cnt));
}

int allocateFreeLoopbackPort()
{
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return 0;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    int port = 0;
    if (bind(fd, reinterpret_cast<const sockaddr *>(&addr), sizeof(addr)) == 0) {
        sockaddr_in bound{};
        socklen_t len = sizeof(bound);
        if (getsockname(fd, reinterpret_cast<sockaddr *>(&bound), &len) == 0) {
            port = ntohs(bound.sin_port);
        }
    }
    close(fd);
    return port;
}

bool extractHelloPeerId(const QByteArray &raw, QString *peerIdOut)
{
    if (raw.size() < 5 || quint8(raw.at(0)) != MsgHello) return false;
    QDataStream ds(raw);
    ds.setByteOrder(QDataStream::LittleEndian);
    quint8 msgType = 0;
    ds >> msgType;
    if (msgType != MsgHello) return false;
    quint32 len = 0;
    ds >> len;
    if (ds.status() != QDataStream::Ok || len == 0 || len > 128) return false;
    if (ds.device()->bytesAvailable() < qint64(len)) return false;
    QByteArray utf8(int(len), Qt::Uninitialized);
    if (ds.readRawData(utf8.data(), int(len)) != int(len)) return false;
    const QString id = QString::fromUtf8(utf8);
    if (id.isEmpty()) return false;
    if (peerIdOut) *peerIdOut = id;
    return true;
}

bool isHighPriorityWireMsg(const QByteArray &bytes)
{
    if (bytes.isEmpty()) return true;
    const quint8 t = quint8(bytes.at(0));
    return t != MsgSnapshotBegin && t != MsgSnapshotChunk && t != MsgLargeChunk && t != MsgTilePatch;
}

/** 计算两个同格式绘图层之间真实发生变化的最小包围盒 (64 行带宽 memcmp 快速跳过未变行) */
QRect computeDeviceDiffRect(const KisPaintDeviceSP &beforeDev,
                            const KisPaintDeviceSP &afterDev,
                            const QRect &searchRect)
{
    if (!beforeDev || !afterDev || searchRect.isEmpty()) return QRect();
    const int ps = int(afterDev->pixelSize());
    if (ps <= 0 || int(beforeDev->pixelSize()) != ps) return searchRect;

    const int x0 = searchRect.x();
    const int y0 = searchRect.y();
    const int w = searchRect.width();
    const int h = searchRect.height();
    const int rowBytes = w * ps;
    if (rowBytes <= 0) return QRect();

    constexpr int kBandRows = 64;
    QByteArray bBuf(rowBytes * kBandRows, Qt::Uninitialized);
    QByteArray aBuf(rowBytes * kBandRows, Qt::Uninitialized);

    int minX = w;
    int maxX = -1;
    int minY = h;
    int maxY = -1;

    for (int ry = 0; ry < h; ry += kBandRows) {
        const int rows = qMin(kBandRows, h - ry);
        beforeDev->readBytes(reinterpret_cast<quint8 *>(bBuf.data()), x0, y0 + ry, w, rows);
        afterDev->readBytes(reinterpret_cast<quint8 *>(aBuf.data()), x0, y0 + ry, w, rows);
        const quint8 *bp = reinterpret_cast<const quint8 *>(bBuf.constData());
        const quint8 *ap = reinterpret_cast<const quint8 *>(aBuf.constData());
        for (int r = 0; r < rows; ++r) {
            const quint8 *br = bp + qint64(r) * rowBytes;
            const quint8 *ar = ap + qint64(r) * rowBytes;
            if (std::memcmp(br, ar, size_t(rowBytes)) == 0) continue;
            const int cy = ry + r;
            if (cy < minY) minY = cy;
            if (cy > maxY) maxY = cy;

            for (int col = 0; col < minX; ++col) {
                if (std::memcmp(br + col * ps, ar + col * ps, size_t(ps)) != 0) {
                    minX = col;
                    break;
                }
            }
            for (int col = w - 1; col > maxX; --col) {
                if (std::memcmp(br + col * ps, ar + col * ps, size_t(ps)) != 0) {
                    maxX = col;
                    break;
                }
            }
        }
    }
    if (maxX < minX || maxY < minY) return QRect();
    return QRect(x0 + minX, y0 + minY, maxX - minX + 1, maxY - minY + 1);
}

QByteArray buildChangedMaskCompressed(const QByteArray &rawBefore,
                                      const QByteArray &rawAfter,
                                      int pixelCount,
                                      int ps)
{
    if (pixelCount <= 0 || ps <= 0 ||
        rawBefore.size() < pixelCount * ps || rawAfter.size() < pixelCount * ps) {
        return QByteArray();
    }
    QByteArray mask(pixelCount, 0);
    const quint8 *b = reinterpret_cast<const quint8 *>(rawBefore.constData());
    const quint8 *a = reinterpret_cast<const quint8 *>(rawAfter.constData());
    quint8 *m = reinterpret_cast<quint8 *>(mask.data());
    if (ps == 4) {
        const quint32 *b32 = reinterpret_cast<const quint32 *>(b);
        const quint32 *a32 = reinterpret_cast<const quint32 *>(a);
        for (int i = 0; i < pixelCount; ++i) {
            m[i] = (b32[i] != a32[i]) ? 1 : 0;
        }
    } else {
        for (int i = 0; i < pixelCount; ++i) {
            m[i] = (std::memcmp(b + qint64(i) * ps, a + qint64(i) * ps, size_t(ps)) != 0) ? 1 : 0;
        }
    }
    return qCompress(mask, 4);
}

} // namespace

namespace reverie {

struct CollabHistoryOp {
    quint64 seq = 0;
    QString authorPeerId;
    quint32 clientOpId = 0;
    QString layerUuid;
    QRect rect;
    bool undone = false;

    bool hasStrokeReplay = false;
    bool nonStrokeEraserLike = false;
    bool strokeAlphaLocked = false;
    QString presetMd5;
    quint32 seed = 0;
    QRgb color = 0;
    float brushSize = 10.0f;
    float opacity = 1.0f;
    int toolMode = 0;
    QVector<CollabSample> samples;

    QByteArray beforeCompressed;
    QByteArray afterCompressed;
    QByteArray nonStrokeMaskCompressed;

    qint64 approxBytes() const
    {
        return beforeCompressed.size() + afterCompressed.size() + nonStrokeMaskCompressed.size()
               + qint64(samples.size()) * qint64(sizeof(CollabSample)) + 128;
    }
};

struct CollabLocalSubOp {
    QString layerUuid;
    QRect rect;
    QByteArray beforeCompressed;
    QByteArray afterCompressed;
    bool hasStrokeReplay = false;
    bool isEraserLike = false;
};

struct CollabLocalStep {
    quint32 clientOpId = 0;
    QVector<CollabLocalSubOp> ops;
};

struct PendingUndoReq {
    QString requesterPeerId;
    quint32 clientOpId = 0;
    bool isRedo = false;
};

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
    bool isHost() const { return m_isHost; }
    bool isActive() const { return m_active; }

    bool start(const QString &roomCode,
               const QString &selfPeerId,
               const QString &selfName,
               const QString &hostPeerId,
               const QString &transportConfigJson,
               const QString &cacheDir)
    {
        stop();

        m_roomCode = roomCode;
        m_selfPeerId = selfPeerId;
        m_selfName = selfName;
        m_hostPeerId = hostPeerId;
        m_isHost = (selfPeerId == hostPeerId);
        m_transportConfigJson = transportConfigJson;
        m_etConfig = parseEasyTierConfig(transportConfigJson, roomCode, selfPeerId, hostPeerId);
        m_cacheDir = cacheDir;
        m_brushCacheDir = cacheDir + QStringLiteral("/brushes");
        m_snapshotTempPath = cacheDir + QStringLiteral("/snapshot_tmp.revp");
        QDir().mkpath(m_brushCacheDir);

        m_hostGlobalSeq = 0;
        m_lastAppliedSeq = 0;
        m_localStrokeSeq = 0;
        m_nextLocalOpId = 0;
        m_hasLoadedSnapshot = m_isHost;
        m_lastWatchdogMs = 0;
        m_seqHistory.clear();
        m_myUndoSteps.clear();
        m_myUndoCursor = 0;
        m_historyOps.clear();
        m_nextHistorySeq = 0;
        m_historyBytes = 0;
        m_pendingLocalStrokeOp = CollabHistoryOp{};
        m_localStrokeLiveMuted = false;
        m_localStrokeUsePatch = false;
        {
            std::lock_guard<std::mutex> slock(m_netStatusMutex);
            m_cachedNetStatusJson = QStringLiteral("{\"ready\":false,\"easytierRunning\":false}");
        }

        m_wakeFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        m_stopNetRequested = false;
        m_needRestartEasyTier = true;
        m_nextDialMs = 0;
        m_addedRemoteEndpoints.clear();
        m_pendingRemoteEndpoints.clear();
        m_lastBroadcastEndpoints.clear();

        m_active = true;
        m_core->m_isCollabProject = true;
        m_core->m_collabRoomCode = roomCode;

        m_netThread = std::thread([this]() { runNetworkLoop(); });
        return true;
    }

    void stop()
    {
        m_active = false;
        m_stopNetRequested = true;
        wakeNetThread();
        if (m_netThread.joinable()) {
            m_netThread.join();
        }
        if (m_wakeFd >= 0) {
            close(m_wakeFd);
            m_wakeFd = -1;
        }

        clearDocumentBoundState();

        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            m_peers.clear();
            m_peerConns.clear();
            m_relayChildren.clear();
            m_relayForwarderId.clear();
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
        m_uploadedBrushMd5s.clear();
        m_incomingBrushes.clear();
        {
            std::lock_guard<std::mutex> lock(m_largeMutex);
            m_incomingLarge.clear();
        }
    }

    void clearDocumentBoundState()
    {
        qDeleteAll(m_remoteStrokes);
        m_remoteStrokes.clear();
        m_localBeforeDev = nullptr;
        m_localBeforeLayerUuid.clear();
        m_localStrokeActive = false;
        m_localStrokeLiveMuted = false;
        m_localStrokeUsePatch = false;
        m_pendingLocalStrokeOp = CollabHistoryOp{};
        m_myUndoSteps.clear();
        m_myUndoCursor = 0;
        m_nextLocalOpId = 0;
        m_historyOps.clear();
        m_nextHistorySeq = 0;
        m_historyBytes = 0;
        m_pendingUndoReqs.clear();
        m_optimisticUndoWatermarks.clear();
        m_incomingSnapshot = IncomingSnapshotAssembly{};
    }

    void onPeerJoined(const QString &peerId, const QString &name, bool isHostPeer)
    {
        if (!m_active || peerId.isEmpty() || peerId == m_selfPeerId) return;

        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            if (!m_peers.contains(peerId)) {
                PeerLink link;
                link.peerId = peerId;
                link.name = name;
                link.isHostPeer = isHostPeer;
                m_peers.insert(peerId, std::move(link));
            } else {
                m_peers[peerId].name = name;
                m_peers[peerId].isHostPeer = isHostPeer;
            }
        }
        wakeNetThread();
        if (m_isHost) {
            recomputeTopologyIfNeeded();
        }
    }

    void onPeerLeft(const QString &peerId)
    {
        if (peerId.isEmpty()) return;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            m_peers.remove(peerId);
            auto cit = m_peerConns.find(peerId);
            if (cit != m_peerConns.end()) {
                if (cit->fd >= 0) close(cit->fd);
                m_peerConns.erase(cit);
            }
        }
        wakeNetThread();
        {
            std::lock_guard<std::mutex> lock(m_cursorMutex);
            m_cursors.remove(peerId);
        }
        cancelRemoteStrokesForPeer(peerId);
        purgeIncomingLargeForPeer(peerId);
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

        QString newHostVip;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto it = m_peers.constFind(newHostPeerId);
            if (it != m_peers.constEnd() && !it->virtualIp.isEmpty()) {
                newHostVip = it->virtualIp;
            }
            for (auto pit = m_peers.begin(); pit != m_peers.end(); ++pit) {
                pit->isHostPeer = (pit.key() == newHostPeerId);
                pit->ctrlOpen = false;
                pit->liveOpen = false;
            }
            for (auto cit = m_peerConns.begin(); cit != m_peerConns.end(); ++cit) {
                if (cit->fd >= 0) close(cit->fd);
            }
            m_peerConns.clear();
        }

        if (!newHostVip.isEmpty()) {
            std::lock_guard<std::mutex> lock(m_netMutex);
            m_etConfig.hostVirtualIp = newHostVip;
        } else {
            const EasyTierTransportConfig fallback = parseEasyTierConfig(
                m_transportConfigJson, m_roomCode, m_selfPeerId, newHostPeerId);
            std::lock_guard<std::mutex> lock(m_netMutex);
            m_etConfig.hostVirtualIp = fallback.hostVirtualIp;
        }

        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            m_needRestartEasyTier = true;
            m_dialRetryCount = 0;
            m_nextDialMs = 0;
        }
        m_uploadedBrushMd5s.clear();
        wakeNetThread();

        if (m_isHost && !wasHost) {
            m_hostGlobalSeq = m_lastAppliedSeq;
            pushUiEvent(QJsonObject{
                {QStringLiteral("type"), QStringLiteral("host_migrated")},
                {QStringLiteral("hostPeerId"), newHostPeerId},
            });
        }
    }

    void onRemoteSignal(const QString &fromPeerId, const QString &payloadJson)
    {
        if (!m_active || fromPeerId.isEmpty() || fromPeerId == m_selfPeerId) return;
        const QJsonDocument doc = QJsonDocument::fromJson(payloadJson.toUtf8());
        if (!doc.isObject()) return;
        const QJsonObject obj = doc.object();
        const QString kind = obj.value(QStringLiteral("kind")).toString();
        if (kind == QLatin1String("et_endpoints")) {
            const QJsonArray uris = obj.value(QStringLiteral("uris")).toArray();
            if (uris.isEmpty()) return;
            std::lock_guard<std::mutex> lock(m_netMutex);
            for (const QJsonValue &v : uris) {
                const QString u = v.toString().trimmed();
                if (!u.isEmpty() && !m_addedRemoteEndpoints.contains(u) &&
                    !m_pendingRemoteEndpoints.contains(u) && m_pendingRemoteEndpoints.size() < 32) {
                    m_pendingRemoteEndpoints.append(u);
                }
            }
            wakeNetThread();
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
        if (!m_core->collabCanEditLayer(idx)) return;
        const QString layerUuid = m_core->m_layers[idx].uuid;
        if (layerUuid.isEmpty()) return;

        KisPaintDeviceSP dev = m_core->layerPaintDeviceFor(m_core->m_layers[idx]);
        if (dev) {
            m_localBeforeDev = new KisPaintDevice(*dev);
            m_localBeforeLayerUuid = layerUuid;
        }
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[idx].node);

        m_nextLocalOpId++;
        m_localStrokeSeq = m_nextLocalOpId;
        m_localStrokeActive = true;
        m_localStrokeLayerUuid = layerUuid;
        m_localSentSampleCount = 0;
        m_localStrokeLiveMuted = m_core->hasSelection();
        m_localStrokeUsePatch = m_localStrokeLiveMuted;

        QString presetMd5;
        QByteArray kppBytes;
        serializeCurrentPreset(presetMd5, kppBytes);

        // 根据引擎与预设实时判定是否擦除 (与 ReverieCoreStroke::flushStrokeBatch 一致)
        bool isEraserPreset = false;
        if (m_core->m_presetIsEraserOverride >= 0) {
            isEraserPreset = (m_core->m_presetIsEraserOverride == 1);
        } else if (m_core->m_brushPreset) {
            const QString pname = m_core->m_brushPreset->name();
            isEraserPreset = pname.startsWith(QLatin1String("a)_Eraser"), Qt::CaseInsensitive) ||
                             pname.contains(QLatin1String("Eraser"), Qt::CaseInsensitive);
        }
        const bool localErasing = (m_core->m_toolMode == ReverieCore::ToolEraser) ||
                                  ((m_core->m_toolMode != ReverieCore::ToolSmudge) && isEraserPreset);
        const int effectiveToolMode = localErasing ? int(ReverieCore::ToolEraser) : int(m_core->m_toolMode);

        const QPointF startPos = m_core->m_strokeSamples.isEmpty()
            ? m_core->m_strokeStartImg
            : m_core->m_strokeSamples.first().imgPos;

        m_pendingLocalStrokeOp = CollabHistoryOp{};
        m_pendingLocalStrokeOp.authorPeerId = m_selfPeerId;
        m_pendingLocalStrokeOp.clientOpId = m_localStrokeSeq;
        m_pendingLocalStrokeOp.layerUuid = layerUuid;
        m_pendingLocalStrokeOp.hasStrokeReplay = true;
        m_pendingLocalStrokeOp.strokeAlphaLocked = (pl && pl->alphaLocked());
        m_pendingLocalStrokeOp.presetMd5 = presetMd5;
        m_pendingLocalStrokeOp.seed = quint32(seed);
        m_pendingLocalStrokeOp.color = m_core->m_strokeColor.rgba();
        m_pendingLocalStrokeOp.brushSize = float(m_core->m_brushSize);
        m_pendingLocalStrokeOp.opacity = float(m_core->m_strokeOpacity);
        m_pendingLocalStrokeOp.toolMode = effectiveToolMode;

        if (m_localStrokeLiveMuted) {
            return;
        }

        // 首次使用该笔刷时主动将 .kpp 发给房主，避免房主收到 commit 后再回发 MsgBrushReq 等候
        if (!m_isHost && !presetMd5.isEmpty() && !kppBytes.isEmpty() &&
            !m_uploadedBrushMd5s.contains(presetMd5) && hasOpenHostCtrl()) {
            m_uploadedBrushMd5s.insert(presetMd5);
            sendBrushChunks(m_hostPeerId, presetMd5, kppBytes);
        }

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
           << quint8(effectiveToolMode)
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
        if (m_localStrokeLiveMuted) return;

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
            m_localBeforeLayerUuid.clear();
            return;
        }
        onLocalStrokeFlushed();

        const bool hadSymmetry = m_core->m_mirrorLiveStarted;
        const bool usePatchOnly = m_localStrokeLiveMuted || hadSymmetry || m_localStrokeUsePatch;
        const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
        const int idx = m_core->layerIndexByUuid(m_localBeforeLayerUuid);
        QRect exactRect;
        CollabHistoryOp op = m_pendingLocalStrokeOp;
        QVector<CollabSample> strokeSamples;
        strokeSamples.reserve(m_core->m_collabPendingSamples.size());
        for (const ReverieCore::StrokeSample &s : m_core->m_collabPendingSamples) {
            strokeSamples.append(CollabSample{
                float(s.imgPos.x()), float(s.imgPos.y()), float(s.pressure),
                float(s.tiltX), float(s.tiltY), float(s.rotation), float(s.time)
            });
        }

        if (m_localBeforeDev && idx >= 0 && idx < m_core->m_layers.size()) {
            KisPaintDeviceSP afterDev = m_core->layerPaintDeviceFor(m_core->m_layers[idx]);
            if (afterDev) {
                const QRect searchRect = (hadSymmetry || dirtyRect.isEmpty())
                    ? docRect
                    : dirtyRect.intersected(docRect);
                exactRect = computeDeviceDiffRect(m_localBeforeDev, afterDev, searchRect);
                if (!exactRect.isEmpty()) {
                    const int pxSize = int(afterDev->pixelSize());
                    const qint64 byteCount = qint64(exactRect.width()) * exactRect.height() * pxSize;
                    if (byteCount > 0 && byteCount <= 64LL * 1024 * 1024) {
                        QByteArray rawBefore(int(byteCount), 0);
                        QByteArray rawAfter(int(byteCount), 0);
                        m_localBeforeDev->readBytes(reinterpret_cast<quint8 *>(rawBefore.data()),
                                                    exactRect.x(), exactRect.y(), exactRect.width(), exactRect.height());
                        afterDev->readBytes(reinterpret_cast<quint8 *>(rawAfter.data()),
                                            exactRect.x(), exactRect.y(), exactRect.width(), exactRect.height());
                        op.rect = exactRect;
                        op.beforeCompressed = qCompress(rawBefore, 4);
                        op.afterCompressed = qCompress(rawAfter, 4);
                        op.nonStrokeEraserLike = (op.toolMode == ReverieCore::ToolEraser);
                        if (usePatchOnly) {
                            op.hasStrokeReplay = false;
                            op.samples.clear();
                            op.nonStrokeMaskCompressed = buildChangedMaskCompressed(
                                rawBefore, rawAfter, exactRect.width() * exactRect.height(), pxSize);
                        } else {
                            op.hasStrokeReplay = true;
                            op.samples = strokeSamples;
                        }
                        CollabLocalSubOp sub;
                        sub.layerUuid = op.layerUuid;
                        sub.rect = exactRect;
                        sub.beforeCompressed = op.beforeCompressed;
                        sub.afterCompressed = op.afterCompressed;
                        sub.hasStrokeReplay = op.hasStrokeReplay;
                        sub.isEraserLike = op.nonStrokeEraserLike;
                        pushMyUndoStep(op.clientOpId, {sub});
                        appendHistoryOp(op);
                    }
                }
            }
        }
        m_localBeforeDev = nullptr;
        m_localBeforeLayerUuid.clear();
        m_pendingLocalStrokeOp = CollabHistoryOp{};
        const bool wasMuted = m_localStrokeLiveMuted;
        const quint32 endedSeq = m_localStrokeSeq;
        m_localStrokeLiveMuted = false;
        m_localStrokeUsePatch = false;
        m_localStrokeActive = false;

        if (exactRect.isEmpty()) {
            m_core->m_collabPendingSamples.clear();
            if (!wasMuted) {
                QByteArray cancelPkt = packMsgHeader(MsgStrokeCancel);
                QDataStream cds(&cancelPkt, QIODevice::Append);
                cds.setByteOrder(QDataStream::LittleEndian);
                writeUtf8(cds, m_selfPeerId);
                cds << endedSeq;
                emitCtrlAction(cancelPkt, /*orderedWithHostSeq=*/false);
            }
            return;
        }

        if (usePatchOnly) {
            m_core->m_collabPendingSamples.clear();
            if (!wasMuted) {
                QByteArray cancelPkt = packMsgHeader(MsgStrokeCancel);
                QDataStream cds(&cancelPkt, QIODevice::Append);
                cds.setByteOrder(QDataStream::LittleEndian);
                writeUtf8(cds, m_selfPeerId);
                cds << endedSeq;
                emitCtrlAction(cancelPkt, /*orderedWithHostSeq=*/false);
            }
            emitTilePatchPacket(op.layerUuid, exactRect, op.afterCompressed, op.clientOpId, op.nonStrokeEraserLike);
            return;
        }

        // Commit 包附带整笔完整采样点 (兜底不可靠通道万一丢失的片段)
        const int total = m_core->m_collabPendingSamples.size();
        QByteArray pkt = packMsgHeader(MsgStrokeCommit);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(endedSeq) << quint32(total);
        for (int i = 0; i < total; ++i) {
            const ReverieCore::StrokeSample &s = m_core->m_collabPendingSamples.at(i);
            ds << float(s.imgPos.x()) << float(s.imgPos.y()) << float(s.pressure)
               << float(s.tiltX) << float(s.tiltY) << float(s.rotation) << float(s.time);
        }
        m_core->m_collabPendingSamples.clear();
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void onLocalStrokeCancel()
    {
        m_localBeforeDev = nullptr;
        m_localBeforeLayerUuid.clear();
        m_pendingLocalStrokeOp = CollabHistoryOp{};
        const bool wasMuted = m_localStrokeLiveMuted;
        m_localStrokeLiveMuted = false;
        m_localStrokeUsePatch = false;
        if (!m_active || !m_localStrokeActive) return;
        m_localStrokeActive = false;
        if (wasMuted) return;
        QByteArray pkt = packMsgHeader(MsgStrokeCancel);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(m_localStrokeSeq);
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/false);
    }

    void recordLocalToolBatch(const QVector<QPair<int, KisPaintDeviceSP>> &beforeList,
                              const QVector<QRect> &hintRects,
                              bool isEraserLike)
    {
        if (!m_active || !m_core->m_document || beforeList.isEmpty()) return;
        const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
        if (docRect.isEmpty()) return;

        const quint32 clientOpId = ++m_nextLocalOpId;
        QVector<CollabLocalSubOp> localSubs;
        QVector<CollabHistoryOp> historyOps;

        for (int i = 0; i < beforeList.size(); ++i) {
            const int idx = beforeList[i].first;
            const KisPaintDeviceSP &beforeDev = beforeList[i].second;
            if (!beforeDev || idx < 0 || idx >= m_core->m_layers.size()) continue;
            const QString layerUuid = m_core->m_layers[idx].uuid;
            if (layerUuid.isEmpty()) continue;
            KisPaintDeviceSP afterDev = m_core->layerPaintDeviceFor(m_core->m_layers[idx]);
            if (!afterDev) continue;

            QRect searchRect = (i < hintRects.size() && hintRects[i].isValid() && !hintRects[i].isEmpty())
                ? hintRects[i].intersected(docRect)
                : (beforeDev->exactBounds() | afterDev->exactBounds()).intersected(docRect);
            if (searchRect.isEmpty()) {
                searchRect = docRect;
            }
            const QRect diffRect = computeDeviceDiffRect(beforeDev, afterDev, searchRect);
            if (diffRect.isEmpty()) continue;

            const int pxSize = int(afterDev->pixelSize());
            const qint64 byteCount = qint64(diffRect.width()) * diffRect.height() * pxSize;
            if (byteCount <= 0 || byteCount > 64LL * 1024 * 1024) continue;

            QByteArray rawBefore(int(byteCount), 0);
            QByteArray rawAfter(int(byteCount), 0);
            beforeDev->readBytes(reinterpret_cast<quint8 *>(rawBefore.data()),
                                 diffRect.x(), diffRect.y(), diffRect.width(), diffRect.height());
            afterDev->readBytes(reinterpret_cast<quint8 *>(rawAfter.data()),
                                diffRect.x(), diffRect.y(), diffRect.width(), diffRect.height());

            CollabHistoryOp op;
            op.authorPeerId = m_selfPeerId;
            op.clientOpId = clientOpId;
            op.layerUuid = layerUuid;
            op.rect = diffRect;
            op.hasStrokeReplay = false;
            op.nonStrokeEraserLike = isEraserLike;
            op.beforeCompressed = qCompress(rawBefore, 4);
            op.afterCompressed = qCompress(rawAfter, 4);
            op.nonStrokeMaskCompressed = buildChangedMaskCompressed(
                rawBefore, rawAfter, diffRect.width() * diffRect.height(), pxSize);

            CollabLocalSubOp sub;
            sub.layerUuid = layerUuid;
            sub.rect = diffRect;
            sub.beforeCompressed = op.beforeCompressed;
            sub.afterCompressed = op.afterCompressed;
            sub.hasStrokeReplay = false;
            sub.isEraserLike = isEraserLike;

            localSubs.append(std::move(sub));
            historyOps.append(std::move(op));
        }

        if (localSubs.isEmpty()) return;
        pushMyUndoStep(clientOpId, localSubs);
        for (CollabHistoryOp &op : historyOps) {
            appendHistoryOp(op);
            emitTilePatchPacket(op.layerUuid, op.rect, op.afterCompressed, clientOpId, isEraserLike);
        }
    }

    void emitLayerOp(int opKind, int index, const QString &extraStr, double extraVal, int extraInt)
    {
        if (!m_active) return;

        // 协同模式下新建图层自动设定本人为默认拥有者 (Magma 专属图层默认值)
        QString targetUuid;
        QString ownerId = m_selfPeerId;
        int perm = 1; // OwnerOnly
        QString aboveUuid;
        if (opKind == ReverieCore::CollabLayerAddPaint ||
            opKind == ReverieCore::CollabLayerAddGroup ||
            opKind == ReverieCore::CollabLayerAddFill) {
            if (index >= 0 && index < m_core->m_layers.size() && m_core->m_layers[index].node) {
                std::lock_guard<std::mutex> mirrorLock(m_core->m_layerMirrorMutex);
                m_core->m_layers[index].ownerPeerId = m_selfPeerId;
                m_core->m_layers[index].collabPermission = 1;
                reverie::setLayerProperty(m_core->m_layers[index].node, "reverie_owner", m_selfPeerId);
                reverie::setLayerProperty(m_core->m_layers[index].node, "reverie_collab_perm", 1);
                targetUuid = m_core->m_layers[index].uuid;
            }
            if (index > 0 && index - 1 < m_core->m_layers.size()) {
                aboveUuid = m_core->m_layers[index - 1].uuid;
            }
        } else if (opKind == ReverieCore::CollabLayerAddMask) {
            if (index > 0 && index < m_core->m_layers.size() && m_core->m_layers[index].node) {
                const int hostIdx = index - 1;
                const int hostPerm = m_core->m_layers[hostIdx].collabPermission;
                const QString &hostOwner = m_core->m_layers[hostIdx].ownerPeerId;
                ownerId = hostOwner.isEmpty() ? m_selfPeerId : hostOwner;
                perm = (hostPerm == 0) ? 0 : 1;
                std::lock_guard<std::mutex> mirrorLock(m_core->m_layerMirrorMutex);
                m_core->m_layers[index].ownerPeerId = ownerId;
                m_core->m_layers[index].collabPermission = perm;
                reverie::setLayerProperty(m_core->m_layers[index].node, "reverie_owner", ownerId);
                reverie::setLayerProperty(m_core->m_layers[index].node, "reverie_collab_perm", perm);
                targetUuid = m_core->m_layers[index].uuid;
                aboveUuid = m_core->m_layers[hostIdx].uuid;
            }
        } else if (opKind == ReverieCore::CollabLayerRemove || opKind == ReverieCore::CollabLayerRemoveMask) {
            targetUuid = extraStr;
        } else if (index >= 0 && index < m_core->m_layers.size()) {
            targetUuid = m_core->m_layers[index].uuid;
            ownerId = m_core->m_layers[index].ownerPeerId;
            perm = m_core->m_layers[index].collabPermission;
            if (opKind == ReverieCore::CollabLayerMoveRelative && m_core->m_layers[index].node) {
                KisNode *prevSib = m_core->m_layers[index].node->prevSibling().data();
                if (prevSib) {
                    const int pSibIdx = m_core->indexOfNode(prevSib);
                    if (pSibIdx >= 0 && pSibIdx < m_core->m_layers.size()) {
                        aboveUuid = m_core->m_layers[pSibIdx].uuid;
                    }
                }
            }
        }
        if (targetUuid.isEmpty()) return;

        QByteArray pkt = packMsgHeader(MsgLayerOp);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        writeUtf8(ds, m_selfPeerId);
        ds << quint8(opKind);
        writeUtf8(ds, targetUuid);
        writeUtf8(ds, extraStr);
        ds << extraVal << qint32(extraInt);
        writeUtf8(ds, ownerId);
        ds << quint8(perm);
        writeUtf8(ds, aboveUuid);

        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void emitLayerPixelsInitial(int index)
    {
        if (!m_active || !m_core->m_document || index <= 0 || index >= m_core->m_layers.size()) return;
        const auto &e = m_core->m_layers[index];
        if (e.uuid.isEmpty() || e.isGroup || e.background) return;
        KisPaintDeviceSP dev = m_core->layerPaintDeviceFor(e);
        if (!dev) return;
        const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
        const QRect ext = dev->exactBounds().intersected(docRect);
        if (ext.isEmpty()) return;

        const bool isMask = (e.nodeType == ReverieCore::NodeTypeTransparencyMask);
        const quint8 defaultByte = isMask ? 0xFF : 0x00;
        const int pxSize = int(dev->pixelSize());
        constexpr int kTile = 512;
        for (int ty = ext.top(); ty <= ext.bottom(); ty += kTile) {
            const int h = qMin(kTile, ext.bottom() - ty + 1);
            for (int tx = ext.left(); tx <= ext.right(); tx += kTile) {
                const int w = qMin(kTile, ext.right() - tx + 1);
                const QRect tileRect(tx, ty, w, h);
                const qint64 byteCount = qint64(w) * h * pxSize;
                if (byteCount <= 0 || byteCount > 64LL * 1024 * 1024) continue;
                QByteArray raw(int(byteCount), 0);
                dev->readBytes(reinterpret_cast<quint8 *>(raw.data()), tx, ty, w, h);
                bool differsFromDefault = false;
                const quint8 *ptr = reinterpret_cast<const quint8 *>(raw.constData());
                for (qint64 i = 0; i < byteCount; ++i) {
                    if (ptr[i] != defaultByte) {
                        differsFromDefault = true;
                        break;
                    }
                }
                if (!differsFromDefault) continue;
                emitTilePatchPacket(e.uuid, tileRect, qCompress(raw, 4), /*clientOpId=*/0, /*isEraserLike=*/false);
            }
        }
    }

    bool canUndo() const { return m_myUndoCursor > 0; }
    bool canRedo() const { return m_myUndoCursor < m_myUndoSteps.size(); }
    int undoCount() const { return m_myUndoCursor; }
    int redoCount() const { return m_myUndoSteps.size() - m_myUndoCursor; }

    bool undo()
    {
        if (!canUndo()) return false;
        const CollabLocalStep step = m_myUndoSteps.at(m_myUndoCursor - 1);
        if (m_isHost) {
            --m_myUndoCursor;
            if (executeSelectiveUndoRedo(m_selfPeerId, step.clientOpId, /*isRedo=*/false, /*broadcastResult=*/true)) {
                return true;
            }
            bool any = false;
            for (int i = step.ops.size() - 1; i >= 0; --i) {
                const CollabLocalSubOp &sub = step.ops.at(i);
                if (applyCompressedPatchToLayer(sub.layerUuid, sub.rect, sub.beforeCompressed, true)) {
                    any = true;
                }
            }
            return any;
        }
        --m_myUndoCursor;
        // 客户端立即在本地应用乐观撤销（0ms 响应），再向房主请求权威合并与全员广播
        bool localApplied = executeSelectiveUndoRedo(m_selfPeerId, step.clientOpId, /*isRedo=*/false, /*broadcastResult=*/false);
        if (!localApplied) {
            for (int i = step.ops.size() - 1; i >= 0; --i) {
                const CollabLocalSubOp &sub = step.ops.at(i);
                if (applyCompressedPatchToLayer(sub.layerUuid, sub.rect, sub.beforeCompressed, false)) {
                    localApplied = true;
                }
            }
        }
        if (hasOpenHostCtrl()) {
            m_optimisticUndoWatermarks.insert(step.clientOpId, m_nextLocalOpId);
            emitUndoReq(step.clientOpId, /*isRedo=*/false);
            return true;
        }
        return localApplied;
    }

    bool redo()
    {
        if (!canRedo()) return false;
        const CollabLocalStep step = m_myUndoSteps.at(m_myUndoCursor);
        if (m_isHost) {
            ++m_myUndoCursor;
            if (executeSelectiveUndoRedo(m_selfPeerId, step.clientOpId, /*isRedo=*/true, /*broadcastResult=*/true)) {
                return true;
            }
            bool any = false;
            for (int i = 0; i < step.ops.size(); ++i) {
                const CollabLocalSubOp &sub = step.ops.at(i);
                if (applyCompressedPatchToLayer(sub.layerUuid, sub.rect, sub.afterCompressed, true)) {
                    any = true;
                }
            }
            return any;
        }
        ++m_myUndoCursor;
        bool localApplied = executeSelectiveUndoRedo(m_selfPeerId, step.clientOpId, /*isRedo=*/true, /*broadcastResult=*/false);
        if (!localApplied) {
            for (int i = 0; i < step.ops.size(); ++i) {
                const CollabLocalSubOp &sub = step.ops.at(i);
                if (applyCompressedPatchToLayer(sub.layerUuid, sub.rect, sub.afterCompressed, false)) {
                    localApplied = true;
                }
            }
        }
        if (hasOpenHostCtrl()) {
            m_optimisticUndoWatermarks.insert(step.clientOpId, m_nextLocalOpId);
            emitUndoReq(step.clientOpId, /*isRedo=*/true);
            return true;
        }
        return localApplied;
    }

    void syncOnReconnect(const QString &newHostPeerId, const QString &transportConfigJson, const QString &peersJson)
    {
        if (!m_active) return;
        bool configChanged = false;
        if (!transportConfigJson.isEmpty() && transportConfigJson != QLatin1String("[]") &&
            transportConfigJson != QLatin1String("{}")) {
            if (m_transportConfigJson != transportConfigJson) {
                m_transportConfigJson = transportConfigJson;
                const EasyTierTransportConfig parsed = parseEasyTierConfig(
                    transportConfigJson, m_roomCode, m_selfPeerId,
                    newHostPeerId.isEmpty() ? m_hostPeerId : newHostPeerId);
                std::lock_guard<std::mutex> lock(m_netMutex);
                if (parsed.networkName != m_etConfig.networkName ||
                    parsed.networkSecret != m_etConfig.networkSecret ||
                    parsed.selfVirtualIp != m_etConfig.selfVirtualIp ||
                    parsed.hostVirtualIp != m_etConfig.hostVirtualIp ||
                    parsed.hostMeshPort != m_etConfig.hostMeshPort ||
                    parsed.relayPeers != m_etConfig.relayPeers) {
                    m_etConfig = parsed;
                    m_needRestartEasyTier = true;
                    configChanged = true;
                }
            }
        }

        const QJsonDocument doc = QJsonDocument::fromJson(peersJson.toUtf8());
        if (doc.isArray()) {
            const QJsonArray arr = doc.array();
            for (const QJsonValue &v : arr) {
                const QJsonObject o = v.toObject();
                const QString pId = o.value(QStringLiteral("peerId")).toString();
                if (pId.isEmpty() || pId == m_selfPeerId) continue;
                const QString pName = o.value(QStringLiteral("name")).toString(pId);
                const QString vIp = o.value(QStringLiteral("virtualIp")).toString();
                const bool isH = o.value(QStringLiteral("isHost")).toBool(pId == m_hostPeerId);
                onPeerJoined(pId, pName, isH);
                if (!vIp.isEmpty()) {
                    std::lock_guard<std::mutex> lock(m_netMutex);
                    if (m_peers.contains(pId)) m_peers[pId].virtualIp = vIp;
                }
            }
        }

        if (!newHostPeerId.isEmpty() && newHostPeerId != m_hostPeerId) {
            onHostChanged(newHostPeerId);
        } else if (configChanged) {
            wakeNetThread();
        }

        if (!m_isHost && !m_hostPeerId.isEmpty()) {
            bool needRetryHost = false;
            {
                std::lock_guard<std::mutex> lock(m_netMutex);
                auto it = m_peers.constFind(m_hostPeerId);
                if (it == m_peers.constEnd() || !it->ctrlOpen) {
                    needRetryHost = true;
                }
            }
            if (needRetryHost) {
                retryPeer(m_hostPeerId);
            }
        }
    }

    void retryPeer(const QString &peerId)
    {
        if (!m_active || peerId.isEmpty() || peerId == m_selfPeerId) return;
        resetPeerConnection(peerId, /*keepLinkMeta=*/true);
        if (!m_isHost && peerId == m_hostPeerId) {
            std::lock_guard<std::mutex> lock(m_netMutex);
            m_dialRetryCount = 0;
            m_nextDialMs = 0;
            m_uploadedBrushMd5s.clear();
        }
        wakeNetThread();
    }

    void resetPeerConnection(const QString &peerId, bool keepLinkMeta)
    {
        bool pushDisconnected = false;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto cit = m_peerConns.find(peerId);
            if (cit != m_peerConns.end()) {
                if (cit->fd >= 0) close(cit->fd);
                m_peerConns.erase(cit);
            }
            auto it = m_peers.find(peerId);
            if (it != m_peers.end()) {
                pushDisconnected = it->ctrlOpen;
                it->ctrlOpen = false;
                it->liveOpen = false;
                it->snapshotSent = false;
                it->ctrlOpenedAtMs = 0;
                it->lastSnapshotReqAtMs = 0;
                if (!keepLinkMeta) {
                    m_peers.erase(it);
                }
            }
        }
        if (pushDisconnected) {
            pushUiEvent(QJsonObject{
                {QStringLiteral("type"), QStringLiteral("peer_p2p_state")},
                {QStringLiteral("peerId"), peerId},
                {QStringLiteral("connected"), false},
            });
        }
        purgeIncomingLargeForPeer(peerId);
        wakeNetThread();
    }

    QString netStatusJson()
    {
        std::lock_guard<std::mutex> lock(m_netStatusMutex);
        return m_cachedNetStatusJson;
    }

    bool drainInbound()
    {
        if (!m_active) return false;
        runP2pWatchdog();
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
            // paintOpOpacity 已在 preparePaintSession 中设置并作用于 previewDev，此处不再二次乘算透明度
            painter.setOpacityToUnit();
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
    QString m_transportConfigJson;
    EasyTierTransportConfig m_etConfig;
    QString m_cacheDir;
    QString m_brushCacheDir;
    QString m_snapshotTempPath;

    quint64 m_hostGlobalSeq = 0;
    quint64 m_lastAppliedSeq = 0;
    quint32 m_localStrokeSeq = 0;
    quint32 m_simStrokeSeq = 0;

    struct TxFrame {
        uint8_t hdr[4]{};
        QByteArray payload;
        size_t offset = 0;
    };

    struct RxFrameState {
        uint8_t hdr[4]{};
        size_t hdrPos = 0;
        quint32 len = 0;
        QByteArray buf;
        size_t pos = 0;
    };

    struct SocketPeerConn {
        int fd = -1;
        bool connecting = false;
        bool handshakeDone = false;
        qint64 connectStartedMs = 0;
        qint64 handshakeDeadlineMs = 0;
        std::deque<TxFrame> hiTx;
        std::deque<TxFrame> loTx;
        TxFrame curTx;
        bool hasCurTx = false;
        RxFrameState rx;
    };

    struct PendingAcceptConn {
        int fd = -1;
        qint64 acceptedAtMs = 0;
        RxFrameState rx;
    };

    std::mutex m_netMutex;
    QHash<QString, PeerLink> m_peers;
    QHash<QString, SocketPeerConn> m_peerConns;
    QString m_relayForwarderId;
    QSet<QString> m_relayChildren;

    std::thread m_netThread;
    std::atomic<bool> m_stopNetRequested{false};
    int m_wakeFd = -1;
    bool m_needRestartEasyTier = false;
    int m_localForwardPort = 0;
    int m_actualHostMeshPort = 0;
    int m_dialRetryCount = 0;
    qint64 m_nextDialMs = 0;
    QStringList m_pendingRemoteEndpoints;
    QSet<QString> m_addedRemoteEndpoints;
    QStringList m_lastBroadcastEndpoints;

    std::mutex m_netStatusMutex;
    QString m_cachedNetStatusJson = QStringLiteral("{\"ready\":false,\"easytierRunning\":false}");

    std::mutex m_inMutex;
    QVector<InboundPacket> m_inboundQueue;

    std::mutex m_uiMutex;
    QVector<QString> m_uiEvents;

    std::mutex m_cursorMutex;
    QHash<QString, CursorEntry> m_cursors;

    QHash<QString, RemoteStrokeSession *> m_remoteStrokes;
    QHash<QString, KisPaintOpPresetSP> m_presetCache;
    QHash<QString, QByteArray> m_presetBytesCache;
    QSet<QString> m_uploadedBrushMd5s;
    QHash<QString, IncomingBrushAssembly> m_incomingBrushes;
    std::mutex m_largeMutex;
    QHash<QString, IncomingLargeAssembly> m_incomingLarge;
    std::atomic<quint32> m_nextChunkMsgId{0};
    IncomingSnapshotAssembly m_incomingSnapshot;

    qint64 m_lastWatchdogMs = 0;
    bool m_hasLoadedSnapshot = false;

    void wakeNetThread()
    {
        if (m_wakeFd >= 0) {
            const uint64_t val = 1;
            const ssize_t n = write(m_wakeFd, &val, sizeof(val));
            (void)n;
        }
    }

    QByteArray buildHelloPacket(bool forceSnapshotOnly) const
    {
        QByteArray hello = packMsgHeader(MsgHello);
        QDataStream ds(&hello, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, m_selfPeerId);
        if (forceSnapshotOnly) {
            writeUtf8(ds, QStringLiteral("__SEND_SNAPSHOT__"));
            ds << kCollabProtoVersion;
        } else {
            writeUtf8(ds, m_selfName);
            ds << kCollabProtoVersion << m_lastAppliedSeq << quint8(m_hasLoadedSnapshot ? 0 : 1);
        }
        return hello;
    }

    static void enqueueFrameToConn(SocketPeerConn &conn, const QByteArray &bytes, bool highPriority)
    {
        if (bytes.isEmpty() || bytes.size() > kMaxWireFrameBytes) return;
        TxFrame frame;
        const quint32 len = quint32(bytes.size());
        frame.hdr[0] = uint8_t((len >> 24) & 0xFF);
        frame.hdr[1] = uint8_t((len >> 16) & 0xFF);
        frame.hdr[2] = uint8_t((len >> 8) & 0xFF);
        frame.hdr[3] = uint8_t(len & 0xFF);
        frame.payload = bytes;
        if (highPriority) {
            conn.hiTx.push_back(std::move(frame));
        } else {
            conn.loTx.push_back(std::move(frame));
        }
    }

    void runP2pWatchdog()
    {
        if (!m_active || m_isHost || m_hostPeerId.isEmpty()) return;
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        if (now - m_lastWatchdogMs < 1500) return;
        m_lastWatchdogMs = now;

        bool needSnapshotReq = false;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto it = m_peers.find(m_hostPeerId);
            if (it == m_peers.end()) return;
            if (it->ctrlOpen) {
                if (!m_hasLoadedSnapshot && !m_incomingSnapshot.active &&
                    it->ctrlOpenedAtMs > 0 && now - it->lastSnapshotReqAtMs >= 4000) {
                    it->lastSnapshotReqAtMs = now;
                    needSnapshotReq = true;
                }
            }
        }
        if (needSnapshotReq) {
            sendBytesToPeer(m_hostPeerId, buildHelloPacket(true), false);
        }
    }

    static QString buildEasyTierToml(const EasyTierTransportConfig &cfg,
                                     const QString &selfPeerId,
                                     bool isHost,
                                     int localForwardPort)
    {
        QString toml;
        toml += QStringLiteral("instance_name = \"%1\"\n").arg(QLatin1String(kEasyTierInstanceName));
        toml += QStringLiteral("hostname = \"%1\"\n").arg(tomlEscape(selfPeerId));
        if (!cfg.selfVirtualIp.isEmpty()) {
            toml += QStringLiteral("ipv4 = \"%1\"\n").arg(tomlEscape(cfg.selfVirtualIp));
        }
        toml += QStringLiteral("dhcp = false\n");
        toml += QStringLiteral("listeners = [\"udp://0.0.0.0:0\", \"tcp://0.0.0.0:0\", \"udp://[::]:0\"]\n");

        if (!cfg.stunServers.isEmpty()) {
            QStringList quoted;
            for (const QString &s : cfg.stunServers) {
                quoted.append(QStringLiteral("\"%1\"").arg(tomlEscape(s)));
            }
            toml += QStringLiteral("stun_servers = [%1]\n").arg(quoted.join(QStringLiteral(", ")));
        }
        if (!cfg.stunServersV6.isEmpty()) {
            QStringList quoted;
            for (const QString &s : cfg.stunServersV6) {
                quoted.append(QStringLiteral("\"%1\"").arg(tomlEscape(s)));
            }
            toml += QStringLiteral("stun_servers_v6 = [%1]\n").arg(quoted.join(QStringLiteral(", ")));
        }

        toml += QStringLiteral("\n[network_identity]\n");
        toml += QStringLiteral("network_name = \"%1\"\n").arg(tomlEscape(cfg.networkName));
        toml += QStringLiteral("network_secret = \"%1\"\n").arg(tomlEscape(cfg.networkSecret));

        for (const QString &relay : cfg.relayPeers) {
            if (relay.isEmpty()) continue;
            toml += QStringLiteral("\n[[peer]]\nuri = \"%1\"\n").arg(tomlEscape(relay));
        }

        if (!isHost && localForwardPort > 0 && !cfg.hostVirtualIp.isEmpty() && cfg.hostMeshPort > 0) {
            toml += QStringLiteral("\n[[port_forward]]\n");
            toml += QStringLiteral("bind_addr = \"127.0.0.1:%1\"\n").arg(localForwardPort);
            toml += QStringLiteral("dst_addr = \"%1:%2\"\n").arg(tomlEscape(cfg.hostVirtualIp)).arg(cfg.hostMeshPort);
            toml += QStringLiteral("proto = \"tcp\"\n");
        }

        toml += QStringLiteral("\n[flags]\n");
        toml += QStringLiteral("no_tun = true\n");
        toml += QStringLiteral("bind_device = false\n");
        toml += QStringLiteral("use_smoltcp = true\n");
        toml += QStringLiteral("enable_kcp_proxy = %1\n").arg(cfg.enableKcpProxy ? QStringLiteral("true") : QStringLiteral("false"));
        toml += QStringLiteral("latency_first = %1\n").arg(cfg.latencyFirst ? QStringLiteral("true") : QStringLiteral("false"));
        toml += QStringLiteral("multi_thread = true\n");
        toml += QStringLiteral("multi_thread_count = 2\n");
        toml += QStringLiteral("enable_ipv6 = true\n");
        toml += QStringLiteral("disable_p2p = false\n");
        return toml;
    }

    static int drainSocketFrames(int fd, RxFrameState &rx, std::vector<QByteArray> &framesOut)
    {
        for (;;) {
            if (rx.hdrPos < 4) {
                const ssize_t n = recv(fd, rx.hdr + rx.hdrPos, 4 - rx.hdrPos, 0);
                if (n > 0) {
                    rx.hdrPos += size_t(n);
                    if (rx.hdrPos == 4) {
                        rx.len = (quint32(rx.hdr[0]) << 24) |
                                 (quint32(rx.hdr[1]) << 16) |
                                 (quint32(rx.hdr[2]) << 8) |
                                 quint32(rx.hdr[3]);
                        if (rx.len == 0 || rx.len > quint32(kMaxWireFrameBytes)) {
                            RPC_LOG("Collab invalid wire frame len=%u", unsigned(rx.len));
                            return -1;
                        }
                        rx.buf.resize(int(rx.len));
                        rx.pos = 0;
                    }
                    continue;
                }
                if (n == 0) return -1;
                if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
                if (errno == EINTR) continue;
                return -1;
            } else {
                const size_t rem = size_t(rx.len) - rx.pos;
                const ssize_t n = recv(fd, rx.buf.data() + rx.pos, rem, 0);
                if (n > 0) {
                    rx.pos += size_t(n);
                    if (rx.pos == size_t(rx.len)) {
                        framesOut.push_back(std::move(rx.buf));
                        rx.buf.clear();
                        rx.hdrPos = 0;
                        rx.len = 0;
                        rx.pos = 0;
                    }
                    continue;
                }
                if (n == 0) return -1;
                if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
                if (errno == EINTR) continue;
                return -1;
            }
        }
    }

    static int flushSocketTx(SocketPeerConn &conn)
    {
        for (;;) {
            if (!conn.hasCurTx) {
                if (!conn.hiTx.empty()) {
                    conn.curTx = std::move(conn.hiTx.front());
                    conn.hiTx.pop_front();
                    conn.curTx.offset = 0;
                    conn.hasCurTx = true;
                } else if (!conn.loTx.empty()) {
                    conn.curTx = std::move(conn.loTx.front());
                    conn.loTx.pop_front();
                    conn.curTx.offset = 0;
                    conn.hasCurTx = true;
                } else {
                    return 0;
                }
            }
            const size_t total = 4 + size_t(conn.curTx.payload.size());
            while (conn.curTx.offset < total) {
                const char *ptr = nullptr;
                size_t chunk = 0;
                if (conn.curTx.offset < 4) {
                    ptr = reinterpret_cast<const char *>(conn.curTx.hdr + conn.curTx.offset);
                    chunk = 4 - conn.curTx.offset;
                } else {
                    const size_t pOff = conn.curTx.offset - 4;
                    ptr = conn.curTx.payload.constData() + pOff;
                    chunk = size_t(conn.curTx.payload.size()) - pOff;
                }
                const ssize_t n = send(conn.fd, ptr, chunk, MSG_NOSIGNAL);
                if (n > 0) {
                    conn.curTx.offset += size_t(n);
                    continue;
                }
                if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                    return 0;
                }
                if (n < 0 && errno == EINTR) continue;
                return -1;
            }
            conn.hasCurTx = false;
            conn.curTx.payload.clear();
        }
    }

    static int openHostLoopbackListener(int preferredPort, int *actualPortOut)
    {
        const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return -1;
        setSocketNonBlocking(fd);
        int one = 1;
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(uint16_t(preferredPort));
        if (bind(fd, reinterpret_cast<const sockaddr *>(&addr), sizeof(addr)) != 0) {
            addr.sin_port = 0;
            if (bind(fd, reinterpret_cast<const sockaddr *>(&addr), sizeof(addr)) != 0) {
                close(fd);
                return -1;
            }
        }
        if (listen(fd, 32) != 0) {
            close(fd);
            return -1;
        }
        sockaddr_in bound{};
        socklen_t len = sizeof(bound);
        if (getsockname(fd, reinterpret_cast<sockaddr *>(&bound), &len) == 0 && actualPortOut) {
            *actualPortOut = ntohs(bound.sin_port);
        }
        return fd;
    }

    static QString natTypeToString(int natInt)
    {
        switch (natInt) {
        case 1: return QStringLiteral("OpenInternet");
        case 2: return QStringLiteral("NoPAT");
        case 3: return QStringLiteral("FullCone");
        case 4: return QStringLiteral("Restricted");
        case 5: return QStringLiteral("PortRestricted");
        case 6: return QStringLiteral("Symmetric");
        case 7: return QStringLiteral("SymUdpFirewall");
        case 8: return QStringLiteral("SymmetricEasyInc");
        case 9: return QStringLiteral("SymmetricEasyDec");
        default: return QStringLiteral("Unknown");
        }
    }

    static int natValToInt(const QJsonValue &v)
    {
        if (v.isDouble()) return v.toInt(0);
        const QString s = v.toString();
        if (s == QLatin1String("OpenInternet")) return 1;
        if (s == QLatin1String("NoPAT")) return 2;
        if (s == QLatin1String("FullCone")) return 3;
        if (s == QLatin1String("Restricted")) return 4;
        if (s == QLatin1String("PortRestricted")) return 5;
        if (s == QLatin1String("Symmetric")) return 6;
        if (s == QLatin1String("SymUdpFirewall")) return 7;
        if (s == QLatin1String("SymmetricEasyInc")) return 8;
        if (s == QLatin1String("SymmetricEasyDec")) return 9;
        return 0;
    }

    static QString formatVirtualIpv4(const QJsonValue &v)
    {
        if (v.isString()) return v.toString();
        if (!v.isObject()) return QString();
        const QJsonObject o = v.toObject();
        const QJsonValue addrV = o.value(QStringLiteral("address"));
        quint32 raw = 0;
        if (addrV.isObject()) {
            raw = quint32(addrV.toObject().value(QStringLiteral("addr")).toDouble(0));
        } else if (addrV.isDouble()) {
            raw = quint32(addrV.toDouble(0));
        }
        if (raw == 0) return QString();
        return QStringLiteral("%1.%2.%3.%4")
            .arg((raw >> 24) & 0xFFu)
            .arg((raw >> 16) & 0xFFu)
            .arg((raw >> 8) & 0xFFu)
            .arg(raw & 0xFFu);
    }

    void pollEasyTierTelemetry(EasyTierApi &api, bool etStarted, const QString &lastErr)
    {
        if (!etStarted) {
            QJsonObject out;
            out.insert(QStringLiteral("ready"), false);
            out.insert(QStringLiteral("easytierRunning"), false);
            out.insert(QStringLiteral("error"), lastErr);
            const QString json = QString::fromUtf8(QJsonDocument(out).toJson(QJsonDocument::Compact));
            std::lock_guard<std::mutex> lock(m_netStatusMutex);
            m_cachedNetStatusJson = json;
            return;
        }

        EtKeyValuePair kv{nullptr, nullptr};
        const int n = api.collectNetworkInfos(&kv, 1);
        if (n < 0) {
            const QString err = api.lastErrorMessage();
            QJsonObject out;
            out.insert(QStringLiteral("ready"), false);
            out.insert(QStringLiteral("easytierRunning"), false);
            out.insert(QStringLiteral("error"), err);
            const QString json = QString::fromUtf8(QJsonDocument(out).toJson(QJsonDocument::Compact));
            std::lock_guard<std::mutex> lock(m_netStatusMutex);
            m_cachedNetStatusJson = json;
            return;
        }
        QString rawInfoJson;
        if (n >= 1) {
            if (kv.value) rawInfoJson = QString::fromUtf8(kv.value);
            if (kv.key) api.freeString(kv.key);
            if (kv.value) api.freeString(kv.value);
        }
        if (rawInfoJson.isEmpty()) return;

        const QJsonDocument doc = QJsonDocument::fromJson(rawInfoJson.toUtf8());
        if (!doc.isObject()) return;
        const QJsonObject root = doc.object();
        const bool running = root.value(QStringLiteral("running")).toBool(false);
        const QString errMsg = root.value(QStringLiteral("error_msg")).toString();
        const QJsonObject myInfo = root.value(QStringLiteral("my_node_info")).toObject();
        const QJsonObject myStun = myInfo.value(QStringLiteral("stun_info")).toObject();

        const QString udpNat = natTypeToString(natValToInt(myStun.value(QStringLiteral("udp_nat_type"))));
        const QString tcpNat = natTypeToString(natValToInt(myStun.value(QStringLiteral("tcp_nat_type"))));
        const QString myVip = formatVirtualIpv4(myInfo.value(QStringLiteral("virtual_ipv4")));

        QJsonArray publicIps;
        QStringList publicV6Addrs;
        const QJsonArray stunPub = myStun.value(QStringLiteral("public_ip")).toArray();
        for (const QJsonValue &pv : stunPub) {
            const QString s = pv.toString().trimmed();
            if (!s.isEmpty()) {
                publicIps.append(s);
                if (s.contains(QLatin1Char(':'))) publicV6Addrs.append(s);
            }
        }

        // 收集可达监听端口与局域网/公网 IPv6 候选 URI, 经信令同步实现无中继直连
        int udpPort = 0;
        int tcpPort = 0;
        const QJsonArray listenersArr = myInfo.value(QStringLiteral("listeners")).toArray();
        for (const QJsonValue &lv : listenersArr) {
            const QString uStr = lv.isObject()
                ? lv.toObject().value(QStringLiteral("url")).toString()
                : lv.toString();
            const int lastColon = uStr.lastIndexOf(QLatin1Char(':'));
            const int port = (lastColon > 0) ? uStr.mid(lastColon + 1).toInt() : 0;
            if (port <= 0) continue;
            if (uStr.startsWith(QLatin1String("udp://")) && udpPort == 0) udpPort = port;
            else if (uStr.startsWith(QLatin1String("tcp://")) && tcpPort == 0) tcpPort = port;
        }
        QStringList localEpUris;
        const QJsonObject ipsObj = myInfo.value(QStringLiteral("ips")).toObject();
        auto extractIps = [&](const QString &field, bool isV6) {
            const QJsonArray arr = ipsObj.value(field).toArray();
            for (const QJsonValue &iv : arr) {
                QString ipStr;
                if (iv.isString()) {
                    ipStr = iv.toString();
                } else if (iv.isObject()) {
                    const QJsonObject io = iv.toObject();
                    if (!isV6) {
                        const quint32 a = quint32(io.value(QStringLiteral("addr")).toDouble(0));
                        if (a != 0) {
                            ipStr = QStringLiteral("%1.%2.%3.%4")
                                .arg((a >> 24) & 0xFFu).arg((a >> 16) & 0xFFu)
                                .arg((a >> 8) & 0xFFu).arg(a & 0xFFu);
                        }
                    }
                }
                if (ipStr.isEmpty() || ipStr.startsWith(QLatin1String("127.")) ||
                    ipStr.startsWith(QLatin1String("10.144.")) || ipStr == QLatin1String("::1") ||
                    ipStr.startsWith(QLatin1String("fe80:"))) continue;
                if (isV6) {
                    if (udpPort > 0) localEpUris.append(QStringLiteral("udp://[%1]:%2").arg(ipStr).arg(udpPort));
                } else {
                    if (udpPort > 0) localEpUris.append(QStringLiteral("udp://%1:%2").arg(ipStr).arg(udpPort));
                    if (tcpPort > 0) localEpUris.append(QStringLiteral("tcp://%1:%2").arg(ipStr).arg(tcpPort));
                }
            }
        };
        extractIps(QStringLiteral("interface_ipv4s"), false);
        extractIps(QStringLiteral("interface_ipv6s"), true);
        for (const QString &v6 : publicV6Addrs) {
            if (udpPort > 0) {
                const QString uri = QStringLiteral("udp://[%1]:%2").arg(v6).arg(udpPort);
                if (!localEpUris.contains(uri)) localEpUris.append(uri);
            }
        }
        if (localEpUris != m_lastBroadcastEndpoints && !localEpUris.isEmpty()) {
            m_lastBroadcastEndpoints = localEpUris;
            QJsonArray epArr;
            for (const QString &u : localEpUris) epArr.append(u);
            pushUiEvent(QJsonObject{
                {QStringLiteral("type"), QStringLiteral("local_endpoints")},
                {QStringLiteral("uris"), epArr},
            });
        }

        QJsonArray peersOut;
        const QJsonArray pairs = root.value(QStringLiteral("peer_route_pairs")).toArray();
        for (const QJsonValue &pv : pairs) {
            const QJsonObject pair = pv.toObject();
            const QJsonObject route = pair.value(QStringLiteral("route")).toObject();
            const QJsonObject peer = pair.value(QStringLiteral("peer")).toObject();
            const QString hostPeer = route.value(QStringLiteral("hostname")).toString();
            if (hostPeer.isEmpty()) continue;

            const int cost = route.value(QStringLiteral("cost")).toInt(0);
            const QString routeMode = (cost <= 1) ? QStringLiteral("p2p") : QStringLiteral("relay");
            const QJsonObject pStun = route.value(QStringLiteral("stun_info")).toObject();
            const QString pNat = natTypeToString(natValToInt(pStun.value(QStringLiteral("udp_nat_type"))));
            const QString pVip = formatVirtualIpv4(route.value(QStringLiteral("ipv4_addr")));

            QString proto;
            int latMs = route.value(QStringLiteral("path_latency")).toInt(-1);
            double loss = 0.0;
            qint64 rxBytes = 0;
            qint64 txBytes = 0;
            const QJsonArray conns = peer.value(QStringLiteral("conns")).toArray();
            if (!conns.isEmpty()) {
                const QJsonObject c0 = conns.first().toObject();
                const QJsonObject tunnel = c0.value(QStringLiteral("tunnel")).toObject();
                proto = tunnel.value(QStringLiteral("tunnel_type")).toString();
                loss = c0.value(QStringLiteral("loss_rate")).toDouble(0.0);
                const QJsonObject stats = c0.value(QStringLiteral("stats")).toObject();
                const qint64 latUs = qint64(stats.value(QStringLiteral("latency_us")).toDouble(-1));
                if (latUs >= 0) latMs = int(latUs / 1000);
                rxBytes = qint64(stats.value(QStringLiteral("rx_bytes")).toDouble(0));
                txBytes = qint64(stats.value(QStringLiteral("tx_bytes")).toDouble(0));
            }
            peersOut.append(QJsonObject{
                {QStringLiteral("peerId"), hostPeer},
                {QStringLiteral("virtualIp"), pVip},
                {QStringLiteral("routeMode"), routeMode},
                {QStringLiteral("tunnelProto"), proto},
                {QStringLiteral("latencyMs"), latMs},
                {QStringLiteral("lossRate"), loss},
                {QStringLiteral("natType"), pNat},
                {QStringLiteral("rxBytes"), rxBytes},
                {QStringLiteral("txBytes"), txBytes},
            });
        }

        EasyTierTransportConfig cfgCopy;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            cfgCopy = m_etConfig;
        }
        QJsonObject statusObj;
        statusObj.insert(QStringLiteral("ready"), running);
        statusObj.insert(QStringLiteral("easytierRunning"), running);
        statusObj.insert(QStringLiteral("selfVirtualIp"), myVip.isEmpty() ? cfgCopy.selfVirtualIp : myVip);
        statusObj.insert(QStringLiteral("hostVirtualIp"), cfgCopy.hostVirtualIp);
        statusObj.insert(QStringLiteral("udpNatType"), udpNat);
        statusObj.insert(QStringLiteral("tcpNatType"), tcpNat);
        statusObj.insert(QStringLiteral("publicIps"), publicIps);
        statusObj.insert(QStringLiteral("error"), errMsg);
        statusObj.insert(QStringLiteral("peers"), peersOut);

        const QString json = QString::fromUtf8(QJsonDocument(statusObj).toJson(QJsonDocument::Compact));
        std::lock_guard<std::mutex> lock(m_netStatusMutex);
        m_cachedNetStatusJson = json;
    }

    void dispatchIncomingFrame(const QString &fromPeerId, QByteArray &&raw)
    {
        if (raw.isEmpty()) return;
        if (quint8(raw.at(0)) == MsgCursor) {
            handleCursorFast(raw);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(m_inMutex);
            m_inboundQueue.append(InboundPacket{fromPeerId, std::move(raw), false});
        }
        pushUiEvent(QJsonObject{{QStringLiteral("type"), QStringLiteral("inbound_ready")}});
    }

    void runNetworkLoop()
    {
        EasyTierApi &api = EasyTierApi::instance();
        QString loadErr;
        const bool apiOk = api.ensureLoaded(&loadErr);
        if (!apiOk) {
            RPC_LOG("Collab EasyTier load failed: %s", loadErr.toUtf8().constData());
            pollEasyTierTelemetry(api, false, loadErr);
            pushUiEvent(QJsonObject{
                {QStringLiteral("type"), QStringLiteral("transport_error")},
                {QStringLiteral("error"), loadErr},
            });
            return;
        }

        bool etStarted = false;
        QString lastEtErr;
        int listenFd = -1;
        std::vector<PendingAcceptConn> pendingAccepts;
        qint64 lastInfoPollMs = 0;

        auto closeAllSockets = [&]() {
            if (listenFd >= 0) {
                close(listenFd);
                listenFd = -1;
            }
            for (auto &pa : pendingAccepts) {
                if (pa.fd >= 0) close(pa.fd);
            }
            pendingAccepts.clear();
            std::vector<QString> droppedPeers;
            {
                std::lock_guard<std::mutex> lock(m_netMutex);
                for (auto cit = m_peerConns.begin(); cit != m_peerConns.end(); ++cit) {
                    if (cit->fd >= 0) close(cit->fd);
                }
                m_peerConns.clear();
                for (auto pit = m_peers.begin(); pit != m_peers.end(); ++pit) {
                    if (pit->ctrlOpen) {
                        pit->ctrlOpen = false;
                        pit->liveOpen = false;
                        droppedPeers.push_back(pit.key());
                    }
                }
            }
            for (const QString &pid : droppedPeers) {
                pushUiEvent(QJsonObject{
                    {QStringLiteral("type"), QStringLiteral("peer_p2p_state")},
                    {QStringLiteral("peerId"), pid},
                    {QStringLiteral("connected"), false},
                });
            }
        };

        while (!m_stopNetRequested) {
            bool doRestart = false;
            EasyTierTransportConfig cfgCopy;
            bool isHostCopy = false;
            QString hostPeerCopy;
            QString selfPeerCopy;
            QStringList endpointsToAdd;

            {
                std::lock_guard<std::mutex> lock(m_netMutex);
                if (m_needRestartEasyTier) {
                    m_needRestartEasyTier = false;
                    doRestart = true;
                }
                cfgCopy = m_etConfig;
                isHostCopy = m_isHost;
                hostPeerCopy = m_hostPeerId;
                selfPeerCopy = m_selfPeerId;
                if (!m_pendingRemoteEndpoints.isEmpty()) {
                    endpointsToAdd.swap(m_pendingRemoteEndpoints);
                }
            }

            if (doRestart) {
                closeAllSockets();
                api.retainNetworkInstance(nullptr, 0);
                etStarted = false;
                m_addedRemoteEndpoints.clear();
                m_dialRetryCount = 0;
                m_nextDialMs = 0;

                if (isHostCopy) {
                    listenFd = openHostLoopbackListener(cfgCopy.hostMeshPort, &m_actualHostMeshPort);
                    if (listenFd < 0) {
                        RPC_LOG("Collab Host listener bind failed port=%d errno=%d",
                                cfgCopy.hostMeshPort, errno);
                    } else if (m_actualHostMeshPort > 0 && m_actualHostMeshPort != cfgCopy.hostMeshPort) {
                        cfgCopy.hostMeshPort = m_actualHostMeshPort;
                        std::lock_guard<std::mutex> lock(m_netMutex);
                        m_etConfig.hostMeshPort = m_actualHostMeshPort;
                    }
                    m_localForwardPort = 0;
                } else {
                    m_localForwardPort = allocateFreeLoopbackPort();
                }

                const QString toml = buildEasyTierToml(cfgCopy, selfPeerCopy, isHostCopy, m_localForwardPort);
                const QByteArray tomlUtf8 = toml.toUtf8();
                const int rc = api.runNetworkInstance(tomlUtf8.constData());
                if (rc == 0) {
                    etStarted = true;
                    lastEtErr.clear();
                    RPC_LOG("EasyTier started inst=%s vip=%s host=%d fwdPort=%d meshPort=%d",
                            kEasyTierInstanceName,
                            cfgCopy.selfVirtualIp.toUtf8().constData(),
                            isHostCopy ? 1 : 0,
                            m_localForwardPort,
                            cfgCopy.hostMeshPort);
                } else {
                    lastEtErr = api.lastErrorMessage();
                    RPC_LOG("EasyTier run_network_instance failed: %s", lastEtErr.toUtf8().constData());
                    pushUiEvent(QJsonObject{
                        {QStringLiteral("type"), QStringLiteral("transport_error")},
                        {QStringLiteral("error"), lastEtErr},
                    });
                }
                pollEasyTierTelemetry(api, etStarted, lastEtErr);
                lastInfoPollMs = QDateTime::currentMSecsSinceEpoch();
            }

            if (etStarted && !endpointsToAdd.isEmpty() && api.callJsonRpc) {
                QJsonArray connectors;
                for (const QString &u : endpointsToAdd) {
                    if (m_addedRemoteEndpoints.contains(u)) continue;
                    m_addedRemoteEndpoints.insert(u);
                    connectors.append(QJsonObject{
                        {QStringLiteral("action"), QStringLiteral("ADD")},
                        {QStringLiteral("url"), QJsonObject{{QStringLiteral("url"), u}}},
                    });
                }
                if (!connectors.isEmpty()) {
                    const QJsonObject rpcPayload{
                        {QStringLiteral("instance"), QJsonObject{
                            {QStringLiteral("instance_selector"), QJsonObject{
                                {QStringLiteral("name"), QLatin1String(kEasyTierInstanceName)}
                            }}
                        }},
                        {QStringLiteral("patch"), QJsonObject{
                            {QStringLiteral("connectors"), connectors}
                        }},
                    };
                    const QByteArray payloadBytes = QJsonDocument(rpcPayload).toJson(QJsonDocument::Compact);
                    const char *respPtr = nullptr;
                    const int rpcRc = api.callJsonRpc(
                        "api.config.ConfigRpc", "patch_config", nullptr,
                        payloadBytes.constData(), &respPtr);
                    if (respPtr) api.freeString(respPtr);
                    if (rpcRc < 0) {
                        RPC_LOG("EasyTier PatchConfig connectors warn: %s",
                                api.lastErrorMessage().toUtf8().constData());
                    }
                }
            }

            const qint64 now = QDateTime::currentMSecsSinceEpoch();

            // Peer 主动向本地 port_forward 端口拨号连往 Host
            if (etStarted && !isHostCopy && m_localForwardPort > 0 && !hostPeerCopy.isEmpty()) {
                bool needDial = false;
                {
                    std::lock_guard<std::mutex> lock(m_netMutex);
                    auto cit = m_peerConns.constFind(hostPeerCopy);
                    if ((cit == m_peerConns.constEnd() || cit->fd < 0) && now >= m_nextDialMs) {
                        needDial = true;
                    }
                }
                if (needDial) {
                    const int sfd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
                    if (sfd >= 0) {
                        configureCollabTcpSocket(sfd);
                        sockaddr_in dst{};
                        dst.sin_family = AF_INET;
                        dst.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
                        dst.sin_port = htons(uint16_t(m_localForwardPort));
                        const int crc = connect(sfd, reinterpret_cast<const sockaddr *>(&dst), sizeof(dst));
                        if (crc == 0 || errno == EINPROGRESS) {
                            SocketPeerConn conn;
                            conn.fd = sfd;
                            conn.connecting = (crc != 0);
                            conn.handshakeDone = false;
                            conn.connectStartedMs = now;
                            conn.handshakeDeadlineMs = now + 15000;
                            if (!conn.connecting) {
                                enqueueFrameToConn(conn, buildHelloPacket(false), true);
                            }
                            std::lock_guard<std::mutex> lock(m_netMutex);
                            m_peerConns.insert(hostPeerCopy, std::move(conn));
                        } else {
                            close(sfd);
                            m_dialRetryCount++;
                            m_nextDialMs = now + qMin<qint64>(3000, 400LL * m_dialRetryCount);
                        }
                    }
                }
            }

            // 构建 pollfd 列表
            std::vector<pollfd> pfds;
            std::vector<QString> pfdPeerKeys;
            std::vector<int> pfdKind; // 0=wake, 1=listen, 2=pending, 3=peerConn

            if (m_wakeFd >= 0) {
                pfds.push_back(pollfd{m_wakeFd, POLLIN, 0});
                pfdPeerKeys.push_back(QString());
                pfdKind.push_back(0);
            }
            if (isHostCopy && listenFd >= 0) {
                pfds.push_back(pollfd{listenFd, POLLIN, 0});
                pfdPeerKeys.push_back(QString());
                pfdKind.push_back(1);
            }
            for (size_t i = 0; i < pendingAccepts.size(); ++i) {
                pfds.push_back(pollfd{pendingAccepts[i].fd, POLLIN, 0});
                pfdPeerKeys.push_back(QString::number(i));
                pfdKind.push_back(2);
            }
            {
                std::lock_guard<std::mutex> lock(m_netMutex);
                for (auto cit = m_peerConns.constBegin(); cit != m_peerConns.constEnd(); ++cit) {
                    if (cit->fd < 0) continue;
                    short ev = POLLIN;
                    if (cit->connecting || cit->hasCurTx || !cit->hiTx.empty() || !cit->loTx.empty()) {
                        ev |= POLLOUT;
                    }
                    pfds.push_back(pollfd{cit->fd, ev, 0});
                    pfdPeerKeys.push_back(cit.key());
                    pfdKind.push_back(3);
                }
            }

            const int prc = poll(pfds.data(), nfds_t(pfds.size()), 50);
            const qint64 afterPollMs = QDateTime::currentMSecsSinceEpoch();

            if (prc > 0) {
                for (size_t i = 0; i < pfds.size(); ++i) {
                    const short rev = pfds[i].revents;
                    if (rev == 0) continue;
                    const int kind = pfdKind[i];

                    if (kind == 0) {
                        uint64_t dummy = 0;
                        while (read(m_wakeFd, &dummy, sizeof(dummy)) > 0) {}
                    } else if (kind == 1) {
                        for (;;) {
                            const int afd = accept4(listenFd, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
                            if (afd < 0) break;
                            configureCollabTcpSocket(afd);
                            if (pendingAccepts.size() >= 64) {
                                close(afd);
                                continue;
                            }
                            PendingAcceptConn pa;
                            pa.fd = afd;
                            pa.acceptedAtMs = afterPollMs;
                            pendingAccepts.push_back(std::move(pa));
                        }
                    } else if (kind == 2) {
                        const int targetFd = pfds[i].fd;
                        auto it = std::find_if(pendingAccepts.begin(), pendingAccepts.end(),
                                               [targetFd](const PendingAcceptConn &p) { return p.fd == targetFd; });
                        if (it == pendingAccepts.end()) continue;
                        std::vector<QByteArray> frames;
                        const int rc = (rev & (POLLERR | POLLHUP))
                            ? -1
                            : drainSocketFrames(it->fd, it->rx, frames);
                        if (rc < 0) {
                            close(it->fd);
                            pendingAccepts.erase(it);
                            continue;
                        }
                        if (!frames.empty()) {
                            QString pid;
                            if (!extractHelloPeerId(frames[0], &pid) || pid == selfPeerCopy) {
                                close(it->fd);
                                pendingAccepts.erase(it);
                                continue;
                            }
                            SocketPeerConn newConn;
                            newConn.fd = it->fd;
                            newConn.handshakeDone = true;
                            newConn.rx = std::move(it->rx);
                            pendingAccepts.erase(it);
                            enqueueFrameToConn(newConn, buildHelloPacket(false), true);
                            {
                                std::lock_guard<std::mutex> lock(m_netMutex);
                                auto oldIt = m_peerConns.find(pid);
                                if (oldIt != m_peerConns.end()) {
                                    if (oldIt->fd >= 0) close(oldIt->fd);
                                    m_peerConns.erase(oldIt);
                                }
                                m_peerConns.insert(pid, std::move(newConn));
                                PeerLink &link = m_peers[pid];
                                if (link.peerId.isEmpty()) {
                                    link.peerId = pid;
                                    link.name = pid;
                                }
                                link.ctrlOpen = true;
                                link.liveOpen = true;
                                link.ctrlOpenedAtMs = afterPollMs;
                                link.lastSnapshotReqAtMs = afterPollMs;
                            }
                            pushUiEvent(QJsonObject{
                                {QStringLiteral("type"), QStringLiteral("peer_p2p_state")},
                                {QStringLiteral("peerId"), pid},
                                {QStringLiteral("connected"), true},
                            });
                            for (QByteArray &fr : frames) {
                                dispatchIncomingFrame(pid, std::move(fr));
                            }
                        }
                    } else if (kind == 3) {
                        const QString &pid = pfdPeerKeys[i];
                        bool dropConn = false;
                        bool emitConnected = false;
                        bool emitDisconnected = false;
                        std::vector<QByteArray> frames;

                        {
                            std::lock_guard<std::mutex> lock(m_netMutex);
                            auto cit = m_peerConns.find(pid);
                            if (cit == m_peerConns.end() || cit->fd != pfds[i].fd) continue;

                            if (rev & POLLOUT) {
                                if (cit->connecting) {
                                    int soErr = 0;
                                    socklen_t slen = sizeof(soErr);
                                    if (getsockopt(cit->fd, SOL_SOCKET, SO_ERROR, &soErr, &slen) != 0 || soErr != 0) {
                                        dropConn = true;
                                    } else {
                                        cit->connecting = false;
                                        enqueueFrameToConn(*cit, buildHelloPacket(false), true);
                                    }
                                }
                                if (!dropConn && !cit->connecting) {
                                    if (flushSocketTx(*cit) < 0) {
                                        dropConn = true;
                                    }
                                }
                            }
                            if (!dropConn && (rev & POLLIN)) {
                                if (drainSocketFrames(cit->fd, cit->rx, frames) < 0) {
                                    dropConn = true;
                                } else if (!frames.empty() && !cit->handshakeDone) {
                                    cit->handshakeDone = true;
                                    PeerLink &link = m_peers[pid];
                                    if (link.peerId.isEmpty()) {
                                        link.peerId = pid;
                                        link.name = pid;
                                        link.isHostPeer = (pid == hostPeerCopy);
                                    }
                                    link.ctrlOpen = true;
                                    link.liveOpen = true;
                                    link.ctrlOpenedAtMs = afterPollMs;
                                    link.lastSnapshotReqAtMs = afterPollMs;
                                    m_dialRetryCount = 0;
                                    emitConnected = true;
                                }
                            }
                            if (!dropConn && (rev & (POLLERR | POLLHUP)) && frames.empty()) {
                                dropConn = true;
                            }
                            if (dropConn) {
                                if (cit->fd >= 0) close(cit->fd);
                                m_peerConns.erase(cit);
                                auto lit = m_peers.find(pid);
                                if (lit != m_peers.end() && lit->ctrlOpen) {
                                    lit->ctrlOpen = false;
                                    lit->liveOpen = false;
                                    emitDisconnected = true;
                                }
                                if (!isHostCopy && pid == hostPeerCopy) {
                                    m_dialRetryCount++;
                                    m_nextDialMs = afterPollMs + qMin<qint64>(3000, 400LL * m_dialRetryCount);
                                }
                            }
                        }

                        if (emitConnected) {
                            pushUiEvent(QJsonObject{
                                {QStringLiteral("type"), QStringLiteral("peer_p2p_state")},
                                {QStringLiteral("peerId"), pid},
                                {QStringLiteral("connected"), true},
                            });
                        }
                        for (QByteArray &fr : frames) {
                            dispatchIncomingFrame(pid, std::move(fr));
                        }
                        if (emitDisconnected) {
                            pushUiEvent(QJsonObject{
                                {QStringLiteral("type"), QStringLiteral("peer_p2p_state")},
                                {QStringLiteral("peerId"), pid},
                                {QStringLiteral("connected"), false},
                            });
                        }
                    }
                }
            }

            // 清理超时的未认证 pending 连接以及 Peer 端等待 MsgHello 超时的连接
            for (auto it = pendingAccepts.begin(); it != pendingAccepts.end();) {
                if (afterPollMs - it->acceptedAtMs > 10000) {
                    close(it->fd);
                    it = pendingAccepts.erase(it);
                } else {
                    ++it;
                }
            }
            if (!isHostCopy && !hostPeerCopy.isEmpty()) {
                bool handshakeTimedOut = false;
                bool hadOpen = false;
                {
                    std::lock_guard<std::mutex> lock(m_netMutex);
                    auto cit = m_peerConns.find(hostPeerCopy);
                    if (cit != m_peerConns.end() && !cit->handshakeDone &&
                        cit->handshakeDeadlineMs > 0 && afterPollMs > cit->handshakeDeadlineMs) {
                        close(cit->fd);
                        m_peerConns.erase(cit);
                        auto lit = m_peers.find(hostPeerCopy);
                        if (lit != m_peers.end() && lit->ctrlOpen) {
                            lit->ctrlOpen = false;
                            lit->liveOpen = false;
                            hadOpen = true;
                        }
                        m_dialRetryCount++;
                        m_nextDialMs = afterPollMs + qMin<qint64>(3000, 500LL * m_dialRetryCount);
                        handshakeTimedOut = true;
                    }
                }
                if (handshakeTimedOut && hadOpen) {
                    pushUiEvent(QJsonObject{
                        {QStringLiteral("type"), QStringLiteral("peer_p2p_state")},
                        {QStringLiteral("peerId"), hostPeerCopy},
                        {QStringLiteral("connected"), false},
                    });
                }
            }

            const qint64 telemetryIntervalMs = m_lastBroadcastEndpoints.isEmpty() ? 400 : 1500;
            if (afterPollMs - lastInfoPollMs >= telemetryIntervalMs) {
                lastInfoPollMs = afterPollMs;
                pollEasyTierTelemetry(api, etStarted, lastEtErr);
            }
        }

        closeAllSockets();
        if (etStarted) {
            api.retainNetworkInstance(nullptr, 0);
        }
    }

    // 房主短历史缓存, 用于大房间/新加入对齐增量
    QVector<QPair<quint64, QByteArray>> m_seqHistory;

    // 本地当前绘制前像素快照 (COW) 与独立选择性撤销历史
    bool m_localStrokeActive = false;
    bool m_localStrokeLiveMuted = false;
    bool m_localStrokeUsePatch = false;
    QString m_localStrokeLayerUuid;
    int m_localSentSampleCount = 0;
    KisPaintDeviceSP m_localBeforeDev;
    QString m_localBeforeLayerUuid;
    CollabHistoryOp m_pendingLocalStrokeOp;

    QVector<CollabLocalStep> m_myUndoSteps;
    int m_myUndoCursor = 0;
    quint32 m_nextLocalOpId = 0;

    QVector<CollabHistoryOp> m_historyOps;
    quint64 m_nextHistorySeq = 0;
    qint64 m_historyBytes = 0;
    QVector<PendingUndoReq> m_pendingUndoReqs;
    QHash<quint32, quint32> m_optimisticUndoWatermarks;

    void pushUiEvent(const QJsonObject &obj)
    {
        const QString raw = QString::fromUtf8(QJsonDocument(obj).toJson(QJsonDocument::Compact));
        std::lock_guard<std::mutex> lock(m_uiMutex);
        m_uiEvents.append(raw);
    }

    void purgeIncomingLargeForPeer(const QString &peerId)
    {
        if (peerId.isEmpty()) return;
        const QString prefix = peerId + QLatin1Char(':');
        std::lock_guard<std::mutex> lock(m_largeMutex);
        for (auto it = m_incomingLarge.begin(); it != m_incomingLarge.end();) {
            if (it.key().startsWith(prefix)) {
                it = m_incomingLarge.erase(it);
            } else {
                ++it;
            }
        }
    }

    void queuePacketListLocked(SocketPeerConn &conn,
                               const QVector<QByteArray> &packets,
                               bool highPriority)
    {
        for (const QByteArray &pkt : packets) {
            enqueueFrameToConn(conn, pkt, highPriority);
        }
    }

    QVector<QByteArray> splitLargePackets(const QByteArray &bytes, bool isLive, quint32 sharedMsgId = 0)
    {
        QVector<QByteArray> out;
        if (bytes.isEmpty()) return out;
        if (isLive || bytes.size() <= kCtrlLargeChunkBytes) {
            out.append(bytes);
            return out;
        }
        const quint32 msgId = (sharedMsgId != 0) ? sharedMsgId : ++m_nextChunkMsgId;
        const quint32 totalChunks = quint32((bytes.size() + kCtrlLargeChunkBytes - 1) / kCtrlLargeChunkBytes);
        out.reserve(int(totalChunks));
        for (quint32 i = 0; i < totalChunks; ++i) {
            const int offset = int(i) * kCtrlLargeChunkBytes;
            const int len = qMin(kCtrlLargeChunkBytes, bytes.size() - offset);
            QByteArray cPkt = packMsgHeader(MsgLargeChunk);
            QDataStream cds(&cPkt, QIODevice::Append);
            cds.setByteOrder(QDataStream::LittleEndian);
            cds << msgId << totalChunks << i << QByteArray::fromRawData(bytes.constData() + offset, len);
            out.append(std::move(cPkt));
        }
        return out;
    }

    void sendBytesToPeer(const QString &peerId, const QByteArray &bytes, bool isLive)
    {
        if (bytes.isEmpty()) return;
        const QVector<QByteArray> pkts = splitLargePackets(bytes, isLive);
        const bool hi = isLive || isHighPriorityWireMsg(bytes);
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            auto cit = m_peerConns.find(peerId);
            if (cit == m_peerConns.end() || cit->fd < 0) return;
            queuePacketListLocked(*cit, pkts, hi);
        }
        wakeNetThread();
    }

    void broadcastBytes(const QByteArray &bytes, bool isLive, const QString &excludePeerId = QString())
    {
        if (bytes.isEmpty()) return;
        const quint32 sharedId = (!isLive && bytes.size() > kCtrlLargeChunkBytes) ? ++m_nextChunkMsgId : 0;
        const QVector<QByteArray> pkts = splitLargePackets(bytes, isLive, sharedId);
        const bool hi = isLive || isHighPriorityWireMsg(bytes);
        bool queuedAny = false;
        {
            std::lock_guard<std::mutex> lock(m_netMutex);
            for (auto cit = m_peerConns.begin(); cit != m_peerConns.end(); ++cit) {
                if (!excludePeerId.isEmpty() && cit.key() == excludePeerId) continue;
                auto lit = m_peers.constFind(cit.key());
                if (m_isHost && lit != m_peers.constEnd() && lit->isRelayChild) continue;
                if (cit->fd < 0 || !cit->handshakeDone) continue;
                queuePacketListLocked(*cit, pkts, hi);
                queuedAny = true;
            }
        }
        if (queuedAny) wakeNetThread();
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

    bool authorizeRemoteLayerOpAsHost(const QString &fromPeerId, const QByteArray &rawPkt) const
    {
        if (!m_core || !m_core->m_document || rawPkt.isEmpty()) return false;
        QDataStream ds(rawPkt);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);

        quint8 type = 0;
        ds >> type;
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
        if (ds.status() != QDataStream::Ok || type != MsgLayerOp ||
            actorPeerId.isEmpty() || actorPeerId != fromPeerId) {
            return false;
        }
        if (actorPeerId == m_hostPeerId) return true;

        auto canManage = [&](int idx) -> bool {
            if (idx <= 0 || idx >= m_core->m_layers.size()) return false;
            const auto &e = m_core->m_layers[idx];
            if (e.background) return false;
            return e.ownerPeerId.isEmpty() || e.ownerPeerId == actorPeerId;
        };
        auto canModifyProps = [&](int idx) -> bool {
            if (idx <= 0 || idx >= m_core->m_layers.size()) return false;
            const auto &e = m_core->m_layers[idx];
            if (e.background || e.locked || e.collabPermission == 2) return false;
            if (e.collabPermission == 1) return e.ownerPeerId.isEmpty() || e.ownerPeerId == actorPeerId;
            return true;
        };
        auto canDelete = [&](int idx) -> bool {
            if (idx <= 0 || idx >= m_core->m_layers.size()) return false;
            const auto &e = m_core->m_layers[idx];
            if (e.background || e.locked || e.collabPermission == 2) return false;
            return canManage(idx);
        };
        auto canInsertGroup = [&](int gIdx) -> bool {
            if (gIdx <= 0 || gIdx >= m_core->m_layers.size()) return false;
            return m_core->m_layers[gIdx].isGroup && canModifyProps(gIdx);
        };

        switch (opKind) {
        case ReverieCore::CollabLayerAddPaint:
        case ReverieCore::CollabLayerAddGroup:
        case ReverieCore::CollabLayerAddFill:
            return true;
        case ReverieCore::CollabLayerAddMask: {
            const int hostIdx = m_core->layerIndexByUuid(aboveUuid);
            return canModifyProps(hostIdx);
        }
        case ReverieCore::CollabLayerBgColor:
            return false;
        case ReverieCore::CollabLayerRemove: {
            const int idx = m_core->layerIndexByUuid(layerUuid);
            if (!canDelete(idx)) return false;
            if (m_core->m_layers[idx].isGroup) {
                const int rootDepth = m_core->m_layers[idx].depth;
                for (int k = idx + 1; k < m_core->m_layers.size() && m_core->m_layers[k].depth > rootDepth; ++k) {
                    if (!canDelete(k)) return false;
                }
            }
            return true;
        }
        case ReverieCore::CollabLayerLocked:
        case ReverieCore::CollabLayerOwnerPerm:
            return canManage(m_core->layerIndexByUuid(layerUuid));
        case ReverieCore::CollabLayerMoveRelative: {
            const int idx = m_core->layerIndexByUuid(layerUuid);
            if (!canModifyProps(idx)) return false;
            KisNode *root = m_core->m_document->rootLayer().data();
            KisNode *dstParent = root;
            if (!extraStr.isEmpty()) {
                const int pIdx = m_core->layerIndexByUuid(extraStr);
                if (pIdx >= 0) {
                    if (!canInsertGroup(pIdx)) return false;
                    dstParent = m_core->m_layers[pIdx].node;
                }
            }
            KisNode *curParent = m_core->m_layers[idx].node ? m_core->m_layers[idx].node->parent().data() : nullptr;
            if (curParent && curParent != root && curParent != dstParent) {
                const int cpIdx = m_core->indexOfNode(curParent);
                // 允许成员将自己新建的图层从受保护组移出到根层或本人组内
                const bool movingOwnOut = (m_core->m_layers[idx].ownerPeerId == actorPeerId);
                if (cpIdx >= 0 && !canInsertGroup(cpIdx) && !movingOwnOut) return false;
            }
            return true;
        }
        case ReverieCore::CollabLayerRename:
        case ReverieCore::CollabLayerVisible:
        case ReverieCore::CollabLayerAlphaLocked:
        case ReverieCore::CollabLayerOpacity:
        case ReverieCore::CollabLayerBlend:
        case ReverieCore::CollabLayerClear:
        case ReverieCore::CollabLayerClipped:
        case ReverieCore::CollabLayerAlphaInherited:
        case ReverieCore::CollabLayerColorLabel:
        case ReverieCore::CollabLayerGroupPassThrough:
        case ReverieCore::CollabLayerRemoveMask:
            return canModifyProps(m_core->layerIndexByUuid(layerUuid));
        default:
            return false;
        }
    }

    bool handleInboundPacket(const InboundPacket &pkt)
    {
        if (pkt.data.isEmpty()) return false;
        QDataStream ds(pkt.data);
        ds.setByteOrder(QDataStream::LittleEndian);
        ds.setFloatingPointPrecision(QDataStream::SinglePrecision);
        quint8 type = 0;
        ds >> type;

        if (type == MsgLargeChunk) {
            quint32 msgId = 0, totalChunks = 0, idx = 0;
            QByteArray chunk;
            ds >> msgId >> totalChunks >> idx >> chunk;
            if (ds.status() != QDataStream::Ok || totalChunks == 0 || totalChunks > 8192 || idx >= totalChunks) {
                return false;
            }
            QByteArray reassembled;
            {
                const QString key = pkt.fromPeerId + QLatin1Char(':') + QString::number(msgId);
                std::lock_guard<std::mutex> lock(m_largeMutex);
                IncomingLargeAssembly &al = m_incomingLarge[key];
                if (al.chunks.size() != int(totalChunks)) {
                    al.totalChunks = totalChunks;
                    al.chunks.resize(int(totalChunks));
                    al.receivedChunks = 0;
                }
                if (al.chunks[int(idx)].isEmpty()) {
                    al.chunks[int(idx)] = chunk;
                    al.receivedChunks++;
                }
                if (al.receivedChunks == al.totalChunks) {
                    int totalBytes = 0;
                    for (const QByteArray &part : al.chunks) totalBytes += part.size();
                    reassembled.reserve(totalBytes);
                    for (const QByteArray &part : al.chunks) reassembled.append(part);
                    m_incomingLarge.remove(key);
                }
            }
            if (reassembled.isEmpty()) return false;
            return handleInboundPacket(InboundPacket{pkt.fromPeerId, std::move(reassembled), pkt.isLiveChannel});
        }

        if (type == MsgHello) {
            const QString id = readUtf8(ds);
            const QString nameOrCmd = readUtf8(ds);
            quint32 protoVer = 0;
            quint64 peerLastSeq = 0;
            quint8 needFull = 1;
            ds >> protoVer;
            if (!ds.atEnd()) {
                ds >> peerLastSeq >> needFull;
            }
            if (nameOrCmd == QLatin1String("__SEND_SNAPSHOT__") && m_isHost) {
                sendDocumentSnapshotToPeer(pkt.fromPeerId);
            } else if (!id.isEmpty()) {
                {
                    std::lock_guard<std::mutex> lock(m_netMutex);
                    if (m_peers.contains(id) && !nameOrCmd.isEmpty()) {
                        m_peers[id].name = nameOrCmd;
                    }
                }
                if (m_isHost && pkt.fromPeerId != m_selfPeerId) {
                    const bool canDeltaReplay = (needFull == 0) &&
                        (peerLastSeq == m_hostGlobalSeq ||
                         (!m_seqHistory.isEmpty() && peerLastSeq >= m_seqHistory.first().first - 1));
                    if (canDeltaReplay) {
                        for (const auto &entry : m_seqHistory) {
                            if (entry.first > peerLastSeq) {
                                sendBytesToPeer(pkt.fromPeerId, entry.second, false);
                            }
                        }
                    } else {
                        sendDocumentSnapshotToPeer(pkt.fromPeerId);
                    }
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

        if (m_isHost && type == MsgLayerOp && !authorizeRemoteLayerOpAsHost(pkt.fromPeerId, pkt.data)) {
            RPC_LOG("Collab host rejected unauthorized MsgLayerOp from=%s", pkt.fromPeerId.toUtf8().constData());
            return false;
        }

        // 若是房主收到普通成员发来的非包装包: 先定序广播再本地执行
        if (m_isHost && type != MsgStrokeDelta && type != MsgStrokeCancel && type != MsgUndoReq) {
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
            quint32 clientOpId = 0;
            quint8 isEraserLike = 0;
            if (!ds.atEnd()) ds >> clientOpId;
            if (!ds.atEnd()) ds >> isEraserLike;
            if (ds.status() != QDataStream::Ok || actorPeerId == m_selfPeerId) return false;
            return applyRemoteTilePatchAndRecord(
                actorPeerId, clientOpId, isEraserLike != 0, layerUuid, QRect(x, y, w, h), compressed);
        }

        if (type == MsgUndoReq) {
            const QString requesterPeerId = readUtf8(ds);
            quint32 clientOpId = 0;
            quint8 isRedo = 0;
            ds >> clientOpId >> isRedo;
            if (ds.status() != QDataStream::Ok || !m_isHost || requesterPeerId.isEmpty() || clientOpId == 0) {
                return false;
            }
            const QString key = requesterPeerId + QLatin1Char(':') + QString::number(clientOpId);
            if (m_remoteStrokes.contains(key)) {
                // 该笔画仍在等待 .kpp 或 commit 完成，入队后在 commitRemoteSession 结束时立刻补执行
                m_pendingUndoReqs.append(PendingUndoReq{requesterPeerId, clientOpId, isRedo != 0});
                return false;
            }
            return executeSelectiveUndoRedo(requesterPeerId, clientOpId, isRedo != 0, /*broadcastResult=*/true);
        }

        if (type == MsgUndoPatch) {
            const QString targetAuthor = readUtf8(ds);
            quint32 targetClientOpId = 0;
            quint8 isRedo = 0;
            ds >> targetClientOpId >> isRedo;
            const QString layerUuid = readUtf8(ds);
            qint32 x = 0, y = 0, w = 0, h = 0;
            quint8 codec = 0;
            QByteArray compressed;
            ds >> x >> y >> w >> h >> codec >> compressed;
            if (ds.status() != QDataStream::Ok) return false;

            const QRect patchRect(x, y, w, h);
            bool skipOverwrite = false;
            if (targetAuthor == m_selfPeerId && m_optimisticUndoWatermarks.contains(targetClientOpId)) {
                const quint32 wm = m_optimisticUndoWatermarks.take(targetClientOpId);
                for (const CollabHistoryOp &op : m_historyOps) {
                    if (!op.undone && op.authorPeerId == m_selfPeerId &&
                        op.clientOpId > wm && op.layerUuid == layerUuid &&
                        op.rect.intersects(patchRect)) {
                        skipOverwrite = true;
                        break;
                    }
                }
            }
            const bool ok = skipOverwrite
                ? true
                : applyCompressedPatchToLayer(layerUuid, patchRect, compressed, false);
            syncPassiveOpUndoFlag(targetAuthor, targetClientOpId, isRedo != 0, layerUuid);
            if (ok) {
                pushUiEvent(QJsonObject{{"type", QStringLiteral("layers_changed")}});
            }
            return ok;
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
        m_hasLoadedSnapshot = true;
        m_core->m_isCollabProject = true;
        m_core->m_collabRoomCode = m_roomCode;
        m_core->m_undoCaptureEnabled = false;
        m_optimisticUndoWatermarks.clear();
        m_pendingUndoReqs.clear();
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
        // 写入本地 .kpp 缓存并保存独立克隆体，避免后续切换橡皮/改参数污染 m_presetCache
        const QString cachedPath = m_brushCacheDir + QLatin1Char('/') + outMd5 + QStringLiteral(".kpp");
        if (!QFile::exists(cachedPath)) {
            QFile f(cachedPath);
            if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
                f.write(outBytes);
                f.close();
            }
        }
        if (!m_presetCache.contains(outMd5)) {
            KisPaintOpPresetSP cloned = m_core->m_brushPreset->clone().dynamicCast<KisPaintOpPreset>();
            if (cloned) {
                if (cloned->settings()) {
                    cloned->settings()->setEraserMode(false);
                }
                m_presetCache.insert(outMd5, cloned);
            }
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
        s->lastPressure = p;
        s->lastTiltX = tx;
        s->lastTiltY = ty;
        s->lastRotation = rot;

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

    bool preparePaintSession(RemoteStrokeSession *s,
                             const KisPaintOpPresetSP &srcPreset,
                             const KisPaintDeviceSP &baseLayerDev,
                             KisNodeSP layerNode)
    {
        if (!s || !srcPreset || !baseLayerDev || !layerNode || !m_core->m_document) return false;

        // 克隆一份独立 preset 防止远端/重放改写 size/opacity/eraserMode 污染本地或其它并发笔画
        KisPaintOpPresetSP cloned = srcPreset->clone().dynamicCast<KisPaintOpPreset>();
        s->preset = cloned ? cloned : srcPreset;

        // 严禁被复用预设上残留的 eraserMode() 劫持为橡皮擦，擦除与否完全以笔画记录的 toolMode 为准
        const bool isEraser = (s->toolMode == ReverieCore::ToolEraser);
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
            // COW 浅拷贝基底层像素供涂抹/液化笔刷采样
            s->previewDev = new KisPaintDevice(*baseLayerDev);
        } else {
            s->previewDev = baseLayerDev->createCompositionSourceDevice();
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
        s->op = KisPaintOpRegistry::instance()->paintOp(s->preset, s->painter, layerNode, m_core->m_document);
        if (!s->op) {
            s->op = new KisBrushOp(s->preset->settings(), s->painter, layerNode, m_core->m_document);
        }
        s->distance = new KisDistanceInformation(s->lastPos, 0.0);
        return s->op != nullptr;
    }

    bool initRemoteStrokePainter(RemoteStrokeSession *s, const KisPaintOpPresetSP &srcPreset)
    {
        if (!s || !srcPreset || !m_core->m_document) return false;
        const int layerIdx = m_core->layerIndexByUuid(s->layerUuid);
        if (layerIdx < 0 || layerIdx >= m_core->m_layers.size()) return false;
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_core->m_layers[layerIdx].node);
        if (!pl || !pl->paintDevice()) return false;
        return preparePaintSession(s, srcPreset, pl->paintDevice(), KisNodeSP(pl));
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
        return paintRemoteSessionSamples(s, fresh, true);
    }

    bool paintRemoteSessionSamples(RemoteStrokeSession *s,
                                   const QVector<CollabSample> &samples,
                                   bool markCanvasDirty = true)
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
                s->lastPressure = cs.pressure;
                s->lastTiltX = cs.tiltX;
                s->lastTiltY = cs.tiltY;
                s->lastRotation = cs.rotation;
                segBounds = QRect(int(cur.x()), int(cur.y()), 1, 1);
            } else {
                const qreal dist = std::hypot(cur.x() - s->lastPos.x(), cur.y() - s->lastPos.y());
                if (dist < 1e-3) continue;
                const StrokePaintSample prevSample{
                    s->lastPos, s->lastPressure, s->lastTiltX, s->lastTiltY, s->lastRotation, s->lastTimeSec};
                const qreal spd = reverie::speedBetween(prevSample, curSample);
                const KisPaintInformation a = makePaintInfo(prevSample, spd, s->randomSource, s->perStrokeRandom);
                const KisPaintInformation b = makePaintInfo(curSample, spd, s->randomSource, s->perStrokeRandom);
                s->op->paintLine(a, b, s->distance);
                s->lastPos = cur;
                s->lastTimeSec = cs.timeSec;
                s->lastPressure = cs.pressure;
                s->lastTiltX = cs.tiltX;
                s->lastTiltY = cs.tiltY;
                s->lastRotation = cs.rotation;
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
        if (markCanvasDirty) {
            m_core->invalidateStaticUnderlayRect(dirty);
            m_core->markStrokeDirty(dirty);
        }
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
                paintRemoteSessionSamples(s, missing, true);
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
                if (KisPaintDeviceSP dev = pl->paintDevice()) {
                    const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
                    QRect searchRect = s->isFullDeviceCopy && !s->dirtyBounds.isEmpty()
                        ? s->dirtyBounds.intersected(docRect)
                        : s->previewDev->exactBounds().intersected(docRect);
                    if (searchRect.isEmpty() && !s->dirtyBounds.isEmpty()) {
                        searchRect = s->dirtyBounds.intersected(docRect);
                    }
                    if (!searchRect.isEmpty()) {
                        KisPaintDeviceSP beforeClone(new KisPaintDevice(*dev));
                        const bool alphaLocked = pl->alphaLocked();
                        KisPainter gc(dev);
                        gc.setCompositeOpId(s->isFullDeviceCopy ? QString(COMPOSITE_COPY) : s->previewCompositeOp);
                        // paintOpOpacity 已作用于 previewDev，合成时保持 1.0 避免透明度平方衰减及橡皮擦不净
                        gc.setOpacityToUnit();
                        if (alphaLocked) {
                            gc.setChannelFlags(pl->channelLockFlags());
                        }
                        gc.bitBlt(searchRect.topLeft(), s->previewDev, searchRect);
                        gc.end();

                        const QRect diffRect = computeDeviceDiffRect(beforeClone, dev, searchRect);
                        if (!diffRect.isEmpty()) {
                            const int pxSize = int(dev->pixelSize());
                            const qint64 byteCount = qint64(diffRect.width()) * diffRect.height() * pxSize;
                            if (byteCount > 0 && byteCount <= 64LL * 1024 * 1024) {
                                QByteArray rawBefore(int(byteCount), 0);
                                QByteArray rawAfter(int(byteCount), 0);
                                beforeClone->readBytes(reinterpret_cast<quint8 *>(rawBefore.data()),
                                                       diffRect.x(), diffRect.y(), diffRect.width(), diffRect.height());
                                dev->readBytes(reinterpret_cast<quint8 *>(rawAfter.data()),
                                               diffRect.x(), diffRect.y(), diffRect.width(), diffRect.height());
                                CollabHistoryOp op;
                                op.authorPeerId = s->peerId;
                                op.clientOpId = s->strokeId;
                                op.layerUuid = s->layerUuid;
                                op.rect = diffRect;
                                op.hasStrokeReplay = true;
                                op.nonStrokeEraserLike = (s->toolMode == ReverieCore::ToolEraser);
                                op.strokeAlphaLocked = alphaLocked;
                                op.presetMd5 = s->presetMd5;
                                op.seed = s->seed;
                                op.color = s->color.rgba();
                                op.brushSize = float(s->brushSize);
                                op.opacity = float(s->opacity);
                                op.toolMode = s->toolMode;
                                op.samples = s->allSamples;
                                op.beforeCompressed = qCompress(rawBefore, 4);
                                op.afterCompressed = qCompress(rawAfter, 4);
                                appendHistoryOp(op);
                            }
                        }

                        m_core->invalidateStaticUnderlayRect(searchRect);
                        m_core->markStrokeDirty(searchRect);
                        m_core->bumpLayerThumbGen(pl);
                        pushUiEvent(QJsonObject{{"type", QStringLiteral("thumb_dirty")}});
                        updated = true;
                    }
                }
            }
        }
        const QString committedPeerId = s->peerId;
        const quint32 committedStrokeId = s->strokeId;
        delete s;
        if (m_isHost && !m_pendingUndoReqs.isEmpty()) {
            for (int i = 0; i < m_pendingUndoReqs.size();) {
                const PendingUndoReq req = m_pendingUndoReqs.at(i);
                if (req.requesterPeerId == committedPeerId && req.clientOpId == committedStrokeId) {
                    m_pendingUndoReqs.removeAt(i);
                    executeSelectiveUndoRedo(req.requesterPeerId, req.clientOpId, req.isRedo, /*broadcastResult=*/true);
                } else {
                    ++i;
                }
            }
        }
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
        const QString savedCurrentUuid =
            (savedCurrent >= 0 && savedCurrent < m_core->m_layers.size())
                ? m_core->m_layers[savedCurrent].uuid
                : QString();

        auto bindRemoteCreatedNode = [&](int createdIdx) {
            if (createdIdx < 0 || createdIdx >= m_core->m_layers.size() || !m_core->m_layers[createdIdx].node) {
                return;
            }
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
        };

        if (opKind == ReverieCore::CollabLayerAddPaint || opKind == ReverieCore::CollabLayerAddGroup) {
            if (m_core->layerIndexByUuid(layerUuid) < 0) {
                const int aboveIdx = m_core->layerIndexByUuid(aboveUuid);
                if (aboveIdx >= 0) {
                    m_core->m_currentLayer = aboveIdx;
                } else if (!m_core->m_layers.isEmpty()) {
                    m_core->m_currentLayer = 0;
                }
                const int createdIdx = (opKind == ReverieCore::CollabLayerAddGroup)
                    ? m_core->addGroupLayer(extraStr)
                    : m_core->addLayer(extraStr);
                bindRemoteCreatedNode(createdIdx);
            }
        } else if (opKind == ReverieCore::CollabLayerAddFill) {
            if (m_core->layerIndexByUuid(layerUuid) < 0) {
                const int aboveIdx = m_core->layerIndexByUuid(aboveUuid);
                if (aboveIdx >= 0) {
                    m_core->m_currentLayer = aboveIdx;
                } else if (!m_core->m_layers.isEmpty()) {
                    m_core->m_currentLayer = 0;
                }
                if (m_core->addLayerWithType(extraStr, ReverieCore::LayerTypeFill, static_cast<quint32>(extraInt))) {
                    bindRemoteCreatedNode(m_core->m_currentLayer);
                }
            }
        } else if (opKind == ReverieCore::CollabLayerAddMask) {
            if (m_core->layerIndexByUuid(layerUuid) < 0) {
                const int hostIdx = m_core->layerIndexByUuid(aboveUuid);
                if (hostIdx > 0 && m_core->addMaskToLayer(hostIdx, ReverieCore::MaskTypeTransparency)) {
                    bindRemoteCreatedNode(m_core->m_currentLayer);
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
                case ReverieCore::CollabLayerClipped:
                    m_core->setLayerClipped(idx, extraInt != 0);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerAlphaInherited:
                    m_core->setLayerAlphaInherited(idx, extraInt != 0);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerColorLabel:
                    m_core->setLayerColorLabel(idx, extraInt);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerGroupPassThrough:
                    m_core->setGroupPassThrough(idx, extraInt != 0);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerBgColor:
                    m_core->setBackgroundColor(static_cast<quint32>(extraInt), true);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerRemoveMask:
                    m_core->removeMask(idx);
                    changed = true;
                    break;
                case ReverieCore::CollabLayerMoveRelative: {
                    KisNodeSP node(m_core->m_layers[idx].node);
                    if (!node || m_core->m_layers[idx].background) break;
                    KisNodeSP root(m_core->m_document->rootLayer());
                    if (!root) break;
                    KisNodeSP parentNode = root;
                    if (!extraStr.isEmpty()) {
                        const int pIdx = m_core->layerIndexByUuid(extraStr);
                        if (pIdx > 0 && pIdx < m_core->m_layers.size() &&
                            m_core->m_layers[pIdx].isGroup && m_core->m_layers[pIdx].node) {
                            parentNode = m_core->m_layers[pIdx].node;
                        }
                    }
                    bool cycle = false;
                    for (KisNodeSP p = parentNode; p; p = p->parent()) {
                        if (p == node) {
                            cycle = true;
                            break;
                        }
                    }
                    if (cycle) break;

                    KisNodeSP prevSib;
                    if (!aboveUuid.isEmpty()) {
                        const int aIdx = m_core->layerIndexByUuid(aboveUuid);
                        if (aIdx >= 0 && aIdx < m_core->m_layers.size() &&
                            m_core->m_layers[aIdx].node &&
                            m_core->m_layers[aIdx].node->parent() == parentNode.data() &&
                            m_core->m_layers[aIdx].node != node.data()) {
                            prevSib = m_core->m_layers[aIdx].node;
                        }
                    }
                    if (!prevSib && !aboveUuid.isEmpty()) {
                        prevSib = parentNode->lastChild();
                        if (prevSib == node) {
                            prevSib = prevSib ? prevSib->prevSibling() : KisNodeSP();
                        }
                    }
                    if (parentNode == root && !prevSib && !m_core->m_layers.isEmpty() &&
                        m_core->m_layers[0].background && m_core->m_layers[0].node) {
                        prevSib = m_core->m_layers[0].node;
                    }
                    if (node->parent() != parentNode.data() || node->prevSibling() != prevSib.data()) {
                        KisImageLayerMoveCommand moveCmd(m_core->m_document, node, parentNode, prevSib);
                        moveCmd.redo();
                        m_core->syncLayersFromImage();
                        m_core->recompositeProjection();
                        m_core->markDirty();
                        changed = true;
                    }
                    break;
                }
                default:
                    break;
                }
            }
        }

        if (!savedCurrentUuid.isEmpty() &&
            opKind != ReverieCore::CollabLayerRemove &&
            opKind != ReverieCore::CollabLayerRemoveMask) {
            const int restoredIdx = m_core->layerIndexByUuid(savedCurrentUuid);
            if (restoredIdx >= 0 && restoredIdx < m_core->m_layers.size()) {
                m_core->m_currentLayer = restoredIdx;
            }
        }
        if (m_core->m_currentLayer >= m_core->m_layers.size()) {
            m_core->m_currentLayer = qMax(0, m_core->m_layers.size() - 1);
        }
        m_core->m_undoCaptureEnabled = prevUndo;
        if (changed) {
            pushUiEvent(QJsonObject{{"type", QStringLiteral("layers_changed")}});
        }
        return changed;
    }

    bool hasOpenHostCtrl()
    {
        if (m_isHost || m_hostPeerId.isEmpty()) return false;
        std::lock_guard<std::mutex> lock(m_netMutex);
        auto it = m_peers.constFind(m_hostPeerId);
        auto cit = m_peerConns.constFind(m_hostPeerId);
        return it != m_peers.constEnd() && it->ctrlOpen &&
               cit != m_peerConns.constEnd() && cit->fd >= 0 && cit->handshakeDone;
    }

    void emitUndoReq(quint32 clientOpId, bool isRedo)
    {
        QByteArray pkt = packMsgHeader(MsgUndoReq);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, m_selfPeerId);
        ds << quint32(clientOpId) << quint8(isRedo ? 1 : 0);
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void emitTilePatchPacket(const QString &layerUuid,
                             const QRect &rect,
                             const QByteArray &compressed,
                             quint32 clientOpId,
                             bool isEraserLike)
    {
        QByteArray pkt = packMsgHeader(MsgTilePatch);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, m_selfPeerId);
        writeUtf8(ds, layerUuid);
        ds << qint32(rect.x()) << qint32(rect.y()) << qint32(rect.width()) << qint32(rect.height())
           << quint8(1) << compressed << quint32(clientOpId) << quint8(isEraserLike ? 1 : 0);
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void emitUndoPatchPacket(const QString &targetAuthor,
                             quint32 targetClientOpId,
                             bool isRedo,
                             const QString &layerUuid,
                             const QRect &rect,
                             const QByteArray &compressed)
    {
        QByteArray pkt = packMsgHeader(MsgUndoPatch);
        QDataStream ds(&pkt, QIODevice::Append);
        ds.setByteOrder(QDataStream::LittleEndian);
        writeUtf8(ds, targetAuthor);
        ds << quint32(targetClientOpId) << quint8(isRedo ? 1 : 0);
        writeUtf8(ds, layerUuid);
        ds << qint32(rect.x()) << qint32(rect.y()) << qint32(rect.width()) << qint32(rect.height())
           << quint8(1) << compressed;
        emitCtrlAction(pkt, /*orderedWithHostSeq=*/true);
    }

    void pushMyUndoStep(quint32 clientOpId, const QVector<CollabLocalSubOp> &ops)
    {
        if (ops.isEmpty()) return;
        if (m_myUndoCursor < m_myUndoSteps.size()) {
            m_myUndoSteps.resize(m_myUndoCursor);
        }
        CollabLocalStep step;
        step.clientOpId = clientOpId;
        step.ops = ops;
        m_myUndoSteps.append(std::move(step));
        if (m_myUndoSteps.size() > kUndoCap) {
            m_myUndoSteps.removeFirst();
        }
        m_myUndoCursor = m_myUndoSteps.size();
    }

    void purgeAuthorRedoOps(const QString &authorPeerId)
    {
        for (int i = m_historyOps.size() - 1; i >= 0; --i) {
            if (m_historyOps.at(i).undone && m_historyOps.at(i).authorPeerId == authorPeerId) {
                m_historyBytes -= m_historyOps.at(i).approxBytes();
                m_historyOps.removeAt(i);
            }
        }
        if (m_historyBytes < 0) m_historyBytes = 0;
    }

    void pruneHistoryIfNeeded()
    {
        while (m_historyOps.size() > kHistoryCap || (m_historyBytes > kHistoryMaxBytes && m_historyOps.size() > 8)) {
            const QString firstAuthor = m_historyOps.first().authorPeerId;
            const quint32 firstOpId = m_historyOps.first().clientOpId;
            while (!m_historyOps.isEmpty() &&
                   m_historyOps.first().authorPeerId == firstAuthor &&
                   m_historyOps.first().clientOpId == firstOpId) {
                m_historyBytes -= m_historyOps.first().approxBytes();
                m_historyOps.removeFirst();
            }
            if (firstAuthor == m_selfPeerId) {
                for (int i = m_myUndoSteps.size() - 1; i >= 0; --i) {
                    if (m_myUndoSteps.at(i).clientOpId == firstOpId) {
                        m_myUndoSteps.removeAt(i);
                        if (i < m_myUndoCursor && m_myUndoCursor > 0) {
                            --m_myUndoCursor;
                        }
                    }
                }
                if (m_myUndoCursor > m_myUndoSteps.size()) {
                    m_myUndoCursor = m_myUndoSteps.size();
                }
            }
        }
        if (m_historyBytes < 0) m_historyBytes = 0;
    }

    void appendHistoryOp(CollabHistoryOp &op)
    {
        purgeAuthorRedoOps(op.authorPeerId);
        op.seq = ++m_nextHistorySeq;
        op.undone = false;
        m_historyBytes += op.approxBytes();
        m_historyOps.append(op);
        pruneHistoryIfNeeded();
    }

    void syncPassiveOpUndoFlag(const QString &authorPeerId, quint32 clientOpId, bool isRedo, const QString &layerUuid)
    {
        for (CollabHistoryOp &op : m_historyOps) {
            if (op.authorPeerId == authorPeerId && op.clientOpId == clientOpId && op.layerUuid == layerUuid) {
                op.undone = !isRedo;
            }
        }
    }

    KisPaintOpPresetSP resolvePresetForReplay(const QString &md5)
    {
        KisPaintOpPresetSP preset = m_presetCache.value(md5);
        if (!preset && !md5.isEmpty()) {
            const QString cachedPath = m_brushCacheDir + QLatin1Char('/') + md5 + QStringLiteral(".kpp");
            if (QFile::exists(cachedPath)) {
                preset = m_core->loadPresetObjectFromPath(cachedPath, md5);
                if (preset) {
                    if (preset->settings()) preset->settings()->setEraserMode(false);
                    m_presetCache.insert(md5, preset);
                }
            }
        }
        if (!preset && m_core->m_brushPreset) {
            KisPaintOpPresetSP fallback = m_core->m_brushPreset->clone().dynamicCast<KisPaintOpPreset>();
            if (fallback && fallback->settings()) {
                fallback->settings()->setEraserMode(false);
            }
            preset = fallback ? fallback : m_core->m_brushPreset;
        }
        return preset;
    }

    bool replayCollabStrokeIntoDevice(KisPaintDeviceSP workingDev,
                                      KisNodeSP layerNode,
                                      const CollabHistoryOp &op,
                                      const QRect &clipRect)
    {
        if (!workingDev || !layerNode || !m_core->m_document || op.samples.isEmpty() || clipRect.isEmpty()) {
            return false;
        }
        KisPaintOpPresetSP preset = resolvePresetForReplay(op.presetMd5);
        if (!preset) return false;

        RemoteStrokeSession tmp;
        tmp.layerUuid = op.layerUuid;
        tmp.presetMd5 = op.presetMd5;
        tmp.seed = op.seed;
        tmp.color = QColor::fromRgba(op.color);
        tmp.brushSize = qBound<qreal>(0.5, qreal(op.brushSize), 1500.0);
        tmp.opacity = qBound<qreal>(0.0, qreal(op.opacity), 1.0);
        tmp.toolMode = op.toolMode;
        const CollabSample &firstSample = op.samples.first();
        tmp.lastPos = QPointF(firstSample.x, firstSample.y);
        tmp.lastTimeSec = double(firstSample.timeSec);
        tmp.lastPressure = firstSample.pressure;
        tmp.lastTiltX = firstSample.tiltX;
        tmp.lastTiltY = firstSample.tiltY;
        tmp.lastRotation = firstSample.rotation;

        if (!preparePaintSession(&tmp, preset, workingDev, layerNode)) {
            return false;
        }
        paintRemoteSessionSamples(&tmp, op.samples, false);

        const QRect ext = tmp.isFullDeviceCopy
            ? clipRect
            : tmp.previewDev->exactBounds().intersected(clipRect);
        if (ext.isEmpty()) {
            return false;
        }
        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(layerNode.data());
        KisPainter gc(workingDev);
        gc.setCompositeOpId(tmp.isFullDeviceCopy ? QString(COMPOSITE_COPY) : tmp.previewCompositeOp);
        // paintOpOpacity 已在 preparePaintSession 作用于 previewDev，合成回 workingDev 时用 1.0
        gc.setOpacityToUnit();
        if (op.strokeAlphaLocked && pl) {
            gc.setChannelFlags(pl->channelLockFlags());
        }
        gc.bitBlt(ext.topLeft(), tmp.previewDev, ext);
        gc.end();
        return true;
    }

    bool replayNonStrokeOpIntoDevice(KisPaintDeviceSP workingDev,
                                      CollabHistoryOp &op,
                                      const QByteArray &oldBeforeRaw,
                                      const QByteArray &oldAfterRaw)
    {
        if (!workingDev || op.rect.isEmpty()) return false;
        const int pxSize = int(workingDev->pixelSize());
        const int pxCount = op.rect.width() * op.rect.height();
        const int expected = pxCount * pxSize;
        if (expected <= 0 || oldBeforeRaw.size() != expected || oldAfterRaw.size() != expected) {
            return false;
        }

        QByteArray newBeforeRaw(expected, 0);
        workingDev->readBytes(reinterpret_cast<quint8 *>(newBeforeRaw.data()),
                              op.rect.x(), op.rect.y(), op.rect.width(), op.rect.height());

        QByteArray mask;
        if (!op.nonStrokeMaskCompressed.isEmpty()) {
            mask = qUncompress(op.nonStrokeMaskCompressed);
        }
        const bool hasMask = (mask.size() == pxCount);
        const quint8 *m = hasMask ? reinterpret_cast<const quint8 *>(mask.constData()) : nullptr;
        const quint8 *ob = reinterpret_cast<const quint8 *>(oldBeforeRaw.constData());
        const quint8 *oa = reinterpret_cast<const quint8 *>(oldAfterRaw.constData());

        QByteArray newAfterRaw = newBeforeRaw;
        quint8 *na = reinterpret_cast<quint8 *>(newAfterRaw.data());
        for (int i = 0; i < pxCount; ++i) {
            const bool touched = hasMask ? (m[i] != 0) : (std::memcmp(ob + qint64(i) * pxSize, oa + qint64(i) * pxSize, size_t(pxSize)) != 0);
            if (touched) {
                std::memcpy(na + qint64(i) * pxSize, oa + qint64(i) * pxSize, size_t(pxSize));
            }
        }

        workingDev->writeBytes(reinterpret_cast<const quint8 *>(newAfterRaw.constData()),
                               op.rect.x(), op.rect.y(), op.rect.width(), op.rect.height());

        const qint64 oldMem = op.approxBytes();
        op.beforeCompressed = qCompress(newBeforeRaw, 4);
        op.afterCompressed = qCompress(newAfterRaw, 4);
        m_historyBytes += (op.approxBytes() - oldMem);
        return true;
    }

    static bool writeCompressedRawToDevice(KisPaintDeviceSP dev, const QRect &rect, const QByteArray &compressed)
    {
        if (!dev || rect.isEmpty() || compressed.isEmpty()) return false;
        const QByteArray raw = qUncompress(compressed);
        const int expected = rect.width() * rect.height() * int(dev->pixelSize());
        if (raw.size() != expected) return false;
        dev->writeBytes(reinterpret_cast<const quint8 *>(raw.constData()),
                        rect.x(), rect.y(), rect.width(), rect.height());
        return true;
    }

    bool executeSelectiveUndoRedo(const QString &authorPeerId, quint32 clientOpId, bool isRedo, bool broadcastResult)
    {
        if (!m_core->m_document || authorPeerId.isEmpty() || clientOpId == 0) return false;
        const QRect docRect(0, 0, m_core->m_docWidth, m_core->m_docHeight);
        if (docRect.isEmpty()) return false;

        QVector<int> targetIndices;
        for (int i = 0; i < m_historyOps.size(); ++i) {
            const CollabHistoryOp &op = m_historyOps.at(i);
            if (op.authorPeerId == authorPeerId && op.clientOpId == clientOpId && op.undone == isRedo) {
                targetIndices.append(i);
            }
        }
        if (targetIndices.isEmpty()) return false;

        bool anyApplied = false;
        const int stepDirBegin = isRedo ? 0 : (targetIndices.size() - 1);
        const int stepDirEnd = isRedo ? targetIndices.size() : -1;
        const int stepInc = isRedo ? 1 : -1;

        for (int ti = stepDirBegin; ti != stepDirEnd; ti += stepInc) {
            const int targetIdx = targetIndices.at(ti);
            CollabHistoryOp &target = m_historyOps[targetIdx];
            const QString layerUuid = target.layerUuid;
            const int layerIdx = m_core->layerIndexByUuid(layerUuid);
            if (layerIdx < 0 || layerIdx >= m_core->m_layers.size()) {
                target.undone = !isRedo;
                continue;
            }
            KisNode *layerNode = m_core->m_layers[layerIdx].node;
            KisPaintDeviceSP layerDev = m_core->layerPaintDeviceFor(m_core->m_layers[layerIdx]);
            if (!layerNode || !layerDev) {
                target.undone = !isRedo;
                continue;
            }

            // 传播闭包计算同一图层上在此操作之后、空间范围相交的所有有效操作 (保留他人重叠笔迹)
            QRect affectedRect = target.rect.intersected(docRect);
            if (affectedRect.isEmpty()) {
                target.undone = !isRedo;
                continue;
            }
            QVector<int> laterIndices;
            QVector<bool> included(m_historyOps.size(), false);
            bool expanded = true;
            while (expanded) {
                expanded = false;
                for (int i = targetIdx + 1; i < m_historyOps.size(); ++i) {
                    if (included[i]) continue;
                    const CollabHistoryOp &later = m_historyOps.at(i);
                    if (later.undone || later.layerUuid != layerUuid) continue;
                    const QRect lr = later.rect.intersected(docRect);
                    if (!lr.isEmpty() && lr.intersects(affectedRect)) {
                        included[i] = true;
                        laterIndices.append(i);
                        affectedRect = affectedRect.united(lr);
                        expanded = true;
                    }
                }
            }
            std::sort(laterIndices.begin(), laterIndices.end());

            // 极速路径: 后续无重叠操作, 直接应用单步局部块
            if (laterIndices.isEmpty()) {
                const int pxSize = int(layerDev->pixelSize());
                if (!isRedo) {
                    if (writeCompressedRawToDevice(layerDev, target.rect, target.beforeCompressed)) {
                        target.undone = true;
                        m_core->invalidateStaticUnderlayRect(target.rect);
                        m_core->recompositeProjection();
                        m_core->markDirty(target.rect);
                        m_core->bumpLayerThumbGen(layerNode);
                        if (broadcastResult) {
                            emitUndoPatchPacket(authorPeerId, clientOpId, false, layerUuid, target.rect, target.beforeCompressed);
                        }
                        anyApplied = true;
                    }
                } else {
                    if (target.hasStrokeReplay) {
                        const qint64 bc = qint64(target.rect.width()) * target.rect.height() * pxSize;
                        QByteArray curBefore(int(bc), 0);
                        layerDev->readBytes(reinterpret_cast<quint8 *>(curBefore.data()),
                                            target.rect.x(), target.rect.y(), target.rect.width(), target.rect.height());
                        if (!replayCollabStrokeIntoDevice(layerDev, KisNodeSP(layerNode), target, target.rect)) {
                            const QByteArray oldB = qUncompress(target.beforeCompressed);
                            const QByteArray oldA = qUncompress(target.afterCompressed);
                            replayNonStrokeOpIntoDevice(layerDev, target, oldB, oldA);
                        } else {
                            QByteArray curAfter(int(bc), 0);
                            layerDev->readBytes(reinterpret_cast<quint8 *>(curAfter.data()),
                                                target.rect.x(), target.rect.y(), target.rect.width(), target.rect.height());
                            const qint64 oldMem = target.approxBytes();
                            target.beforeCompressed = qCompress(curBefore, 4);
                            target.afterCompressed = qCompress(curAfter, 4);
                            m_historyBytes += (target.approxBytes() - oldMem);
                        }
                    } else {
                        const QByteArray oldB = qUncompress(target.beforeCompressed);
                        const QByteArray oldA = qUncompress(target.afterCompressed);
                        replayNonStrokeOpIntoDevice(layerDev, target, oldB, oldA);
                    }
                    target.undone = false;
                    m_core->invalidateStaticUnderlayRect(target.rect);
                    m_core->recompositeProjection();
                    m_core->markDirty(target.rect);
                    m_core->bumpLayerThumbGen(layerNode);
                    if (broadcastResult) {
                        emitUndoPatchPacket(authorPeerId, clientOpId, true, layerUuid, target.rect, target.afterCompressed);
                    }
                    anyApplied = true;
                }
                continue;
            }

            // 重叠路径: 在隔离画布块上按时序回滚 -> 切换目标状态 -> 无损重放其余所有人后续笔迹/工具
            const int pxSize = int(layerDev->pixelSize());
            const qint64 byteCount = qint64(affectedRect.width()) * affectedRect.height() * pxSize;
            if (byteCount <= 0 || byteCount > 64LL * 1024 * 1024) continue;

            KisPaintDeviceSP workingDev(new KisPaintDevice(layerDev->colorSpace()));
            QByteArray initialRaw(int(byteCount), 0);
            layerDev->readBytes(reinterpret_cast<quint8 *>(initialRaw.data()),
                                affectedRect.x(), affectedRect.y(), affectedRect.width(), affectedRect.height());
            workingDev->writeBytes(reinterpret_cast<const quint8 *>(initialRaw.constData()),
                                   affectedRect.x(), affectedRect.y(), affectedRect.width(), affectedRect.height());

            QByteArray targetOldB;
            QByteArray targetOldA;
            if (isRedo) {
                targetOldB = qUncompress(target.beforeCompressed);
                targetOldA = qUncompress(target.afterCompressed);
            }
            QVector<QByteArray> laterOldB(laterIndices.size());
            QVector<QByteArray> laterOldA(laterIndices.size());
            for (int k = 0; k < laterIndices.size(); ++k) {
                const CollabHistoryOp &lop = m_historyOps.at(laterIndices.at(k));
                laterOldB[k] = qUncompress(lop.beforeCompressed);
                laterOldA[k] = qUncompress(lop.afterCompressed);
            }

            for (int k = laterIndices.size() - 1; k >= 0; --k) {
                const CollabHistoryOp &lop = m_historyOps.at(laterIndices.at(k));
                writeCompressedRawToDevice(workingDev, lop.rect, lop.beforeCompressed);
            }

            if (!isRedo) {
                // Undo: 必须回滚 target 本身到 target 执行前像素状态
                writeCompressedRawToDevice(workingDev, target.rect, target.beforeCompressed);
                target.undone = true;
            } else {
                // Redo: target 原本不在画面上, workingDev 已是当前真实状态, 重新执行 target
                const qint64 tByteCount = qint64(target.rect.width()) * target.rect.height() * pxSize;
                if (target.hasStrokeReplay && tByteCount > 0) {
                    QByteArray newTB(int(tByteCount), 0);
                    workingDev->readBytes(reinterpret_cast<quint8 *>(newTB.data()),
                                          target.rect.x(), target.rect.y(), target.rect.width(), target.rect.height());
                    if (!replayCollabStrokeIntoDevice(workingDev, KisNodeSP(layerNode), target, target.rect)) {
                        replayNonStrokeOpIntoDevice(workingDev, target, targetOldB, targetOldA);
                    } else {
                        QByteArray newTA(int(tByteCount), 0);
                        workingDev->readBytes(reinterpret_cast<quint8 *>(newTA.data()),
                                              target.rect.x(), target.rect.y(), target.rect.width(), target.rect.height());
                        const qint64 oldMem = target.approxBytes();
                        target.beforeCompressed = qCompress(newTB, 4);
                        target.afterCompressed = qCompress(newTA, 4);
                        m_historyBytes += (target.approxBytes() - oldMem);
                    }
                } else {
                    replayNonStrokeOpIntoDevice(workingDev, target, targetOldB, targetOldA);
                }
                target.undone = false;
            }

            // 顺次重放后续所有依然活跃的操作并刷新它们的 before/after 缓存
            for (int k = 0; k < laterIndices.size(); ++k) {
                CollabHistoryOp &lop = m_historyOps[laterIndices.at(k)];
                if (lop.hasStrokeReplay) {
                    const qint64 lByteCount = qint64(lop.rect.width()) * lop.rect.height() * pxSize;
                    if (lByteCount > 0) {
                        QByteArray newB(int(lByteCount), 0);
                        workingDev->readBytes(reinterpret_cast<quint8 *>(newB.data()),
                                              lop.rect.x(), lop.rect.y(), lop.rect.width(), lop.rect.height());
                        if (!replayCollabStrokeIntoDevice(workingDev, KisNodeSP(layerNode), lop, lop.rect)) {
                            replayNonStrokeOpIntoDevice(workingDev, lop, laterOldB[k], laterOldA[k]);
                        } else {
                            QByteArray newA(int(lByteCount), 0);
                            workingDev->readBytes(reinterpret_cast<quint8 *>(newA.data()),
                                                  lop.rect.x(), lop.rect.y(), lop.rect.width(), lop.rect.height());
                            const qint64 oldMem = lop.approxBytes();
                            lop.beforeCompressed = qCompress(newB, 4);
                            lop.afterCompressed = qCompress(newA, 4);
                            m_historyBytes += (lop.approxBytes() - oldMem);
                        }
                    }
                } else {
                    replayNonStrokeOpIntoDevice(workingDev, lop, laterOldB[k], laterOldA[k]);
                }
            }

            QByteArray finalRaw(int(byteCount), 0);
            workingDev->readBytes(reinterpret_cast<quint8 *>(finalRaw.data()),
                                  affectedRect.x(), affectedRect.y(), affectedRect.width(), affectedRect.height());
            layerDev->writeBytes(reinterpret_cast<const quint8 *>(finalRaw.constData()),
                                 affectedRect.x(), affectedRect.y(), affectedRect.width(), affectedRect.height());

            m_core->invalidateStaticUnderlayRect(affectedRect);
            m_core->recompositeProjection();
            m_core->markDirty(affectedRect);
            m_core->bumpLayerThumbGen(layerNode);

            if (broadcastResult) {
                const QByteArray compressedFinal = qCompress(finalRaw, 4);
                emitUndoPatchPacket(authorPeerId, clientOpId, isRedo, layerUuid, affectedRect, compressedFinal);
            }
            anyApplied = true;
        }
        return anyApplied;
    }

    bool applyRemoteTilePatchAndRecord(const QString &actorPeerId,
                                       quint32 clientOpId,
                                       bool isEraserLike,
                                       const QString &layerUuid,
                                       const QRect &rect,
                                       const QByteArray &compressed)
    {
        if (!m_core->m_document || rect.isEmpty() || compressed.isEmpty()) return false;
        const int idx = m_core->layerIndexByUuid(layerUuid);
        if (idx < 0 || idx >= m_core->m_layers.size()) return false;
        KisNode *node = m_core->m_layers[idx].node;
        KisPaintDeviceSP dev = m_core->layerPaintDeviceFor(m_core->m_layers[idx]);
        if (!node || !dev) return false;

        const QByteArray rawAfter = qUncompress(compressed);
        const int pxSize = int(dev->pixelSize());
        const int pxCount = rect.width() * rect.height();
        const int expected = pxCount * pxSize;
        if (expected <= 0 || rawAfter.size() != expected) return false;

        QByteArray rawBefore(expected, 0);
        dev->readBytes(reinterpret_cast<quint8 *>(rawBefore.data()),
                       rect.x(), rect.y(), rect.width(), rect.height());

        dev->writeBytes(reinterpret_cast<const quint8 *>(rawAfter.constData()),
                        rect.x(), rect.y(), rect.width(), rect.height());
        m_core->invalidateStaticUnderlayRect(rect);
        m_core->recompositeProjection();
        m_core->markDirty(rect);
        m_core->bumpLayerThumbGen(node);

        if (clientOpId > 0) {
            CollabHistoryOp op;
            op.authorPeerId = actorPeerId;
            op.clientOpId = clientOpId;
            op.layerUuid = layerUuid;
            op.rect = rect;
            op.hasStrokeReplay = false;
            op.nonStrokeEraserLike = isEraserLike;
            op.beforeCompressed = qCompress(rawBefore, 4);
            op.afterCompressed = compressed;
            op.nonStrokeMaskCompressed = buildChangedMaskCompressed(rawBefore, rawAfter, pxCount, pxSize);
            appendHistoryOp(op);
        }
        return true;
    }

    bool applyCompressedPatchToLayer(const QString &layerUuid, const QRect &rect,
                                     const QByteArray &compressed, bool broadcastPatch)
    {
        if (!m_core->m_document || rect.isEmpty() || compressed.isEmpty()) return false;
        const int idx = m_core->layerIndexByUuid(layerUuid);
        if (idx < 0 || idx >= m_core->m_layers.size()) return false;
        KisNode *node = m_core->m_layers[idx].node;
        KisPaintDeviceSP dev = m_core->layerPaintDeviceFor(m_core->m_layers[idx]);
        if (!node || !dev) return false;

        const QByteArray raw = qUncompress(compressed);
        const int expected = rect.width() * rect.height() * int(dev->pixelSize());
        if (raw.size() != expected) return false;

        dev->writeBytes(reinterpret_cast<const quint8 *>(raw.constData()),
                        rect.x(), rect.y(), rect.width(), rect.height());
        m_core->invalidateStaticUnderlayRect(rect);
        m_core->recompositeProjection();
        m_core->markDirty(rect);
        m_core->bumpLayerThumbGen(node);

        if (broadcastPatch) {
            emitTilePatchPacket(layerUuid, rect, compressed, 0, false);
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
    // 房主建房时, 为所有已有非背景图层补齐房主专属所有权 (若原为空)
    if (selfPeerId == hostPeerId) {
        std::lock_guard<std::mutex> mirrorLock(m_layerMirrorMutex);
        for (int i = 1; i < m_layers.size(); ++i) {
            if (m_layers[i].node && m_layers[i].ownerPeerId.isEmpty()) {
                m_layers[i].ownerPeerId = selfPeerId;
                m_layers[i].collabPermission = 1;
                reverie::setLayerProperty(m_layers[i].node, "reverie_owner", selfPeerId);
                reverie::setLayerProperty(m_layers[i].node, "reverie_collab_perm", 1);
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

bool ReverieCore::collabIsHost() const
{
    return !m_collabActive || !m_collab || m_collab->isHost();
}

bool ReverieCore::collabCanEditBackground() const
{
    return !m_collabActive || !m_collab || m_collab->isHost();
}

bool ReverieCore::collabCanManageLayerOwnership(int index) const
{
    if (index < 0 || index >= m_layers.size()) return false;
    if (!m_collabActive || !m_collab) return true;
    if (m_collab->isHost()) return true;
    const QString &owner = m_layers[index].ownerPeerId;
    return owner.isEmpty() || owner == m_collab->selfPeerId();
}

bool ReverieCore::collabCanModifyLayerProps(int index) const
{
    if (index < 0 || index >= m_layers.size()) return false;
    if (!m_collabActive || !m_collab) return true;
    const LayerEntry &e = m_layers[index];
    if (e.background) return collabCanEditBackground();
    if (e.locked) return false;
    if (e.collabPermission == 2) return false;
    if (e.collabPermission == 1) {
        return collabCanManageLayerOwnership(index);
    }
    return true;
}

bool ReverieCore::collabCanDeleteLayer(int index) const
{
    if (index <= 0 || index >= m_layers.size()) return false;
    const LayerEntry &e = m_layers[index];
    if (e.background || e.locked) return false;
    if (!m_collabActive || !m_collab) return true;
    if (e.collabPermission == 2) return false;
    return collabCanManageLayerOwnership(index);
}

bool ReverieCore::collabCanInsertIntoGroup(int groupIndex) const
{
    if (groupIndex <= 0 || groupIndex >= m_layers.size()) return false;
    const LayerEntry &g = m_layers[groupIndex];
    if (!g.isGroup || g.locked) return false;
    if (!m_collabActive || !m_collab) return true;
    if (g.collabPermission == 2) return false;
    if (g.collabPermission == 1) {
        return collabCanManageLayerOwnership(groupIndex);
    }
    return true;
}

bool ReverieCore::collabIsNodeTypeSupported(int nodeType) const
{
    return nodeType == NodeTypePaint ||
           nodeType == NodeTypeGroup ||
           nodeType == NodeTypeFill ||
           nodeType == NodeTypeTransparencyMask;
}

bool ReverieCore::collabCanCopySubtree(int index) const
{
    if (index <= 0 || index >= m_layers.size()) return false;
    if (!m_collabActive || !m_collab) return true;
    if (!collabIsNodeTypeSupported(m_layers[index].nodeType) || m_layers[index].isStrokeLayer) {
        return false;
    }
    if (m_layers[index].isGroup) {
        const int rootDepth = m_layers[index].depth;
        for (int i = index + 1; i < m_layers.size() && m_layers[i].depth > rootDepth; ++i) {
            if (!collabIsNodeTypeSupported(m_layers[i].nodeType) || m_layers[i].isStrokeLayer) {
                return false;
            }
        }
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

void ReverieCore::collabSyncOnReconnect(const QString &hostPeerId, const QString &iceServersJson, const QString &peersJson)
{
    if (m_collab) m_collab->syncOnReconnect(hostPeerId, iceServersJson, peersJson);
}

void ReverieCore::collabRetryPeer(const QString &peerId)
{
    if (m_collab) m_collab->retryPeer(peerId);
}

void ReverieCore::setProjectCollabMeta(bool isCollab, const QString &roomCode)
{
    m_isCollabProject = isCollab;
    m_collabRoomCode = roomCode;
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

QString ReverieCore::collabNetStatusJson()
{
    return m_collab ? m_collab->netStatusJson() : QStringLiteral("{\"ready\":false,\"easytierRunning\":false}");
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

void ReverieCore::collabEmitLayerPixelsInitial(int index)
{
    if (m_collab) m_collab->emitLayerPixelsInitial(index);
}

void ReverieCore::collabEmitLayerMoved(const QVector<KisNode *> &movedNodes)
{
    if (!m_collabActive || m_collabSuppressEmit || !m_collab || !m_document) return;
    QVector<QPair<int, KisNode *>> ordered;
    ordered.reserve(movedNodes.size());
    for (KisNode *n : movedNodes) {
        if (!n) continue;
        const int idx = indexOfNode(n);
        if (idx > 0 && idx < m_layers.size()) {
            ordered.append(qMakePair(idx, n));
        }
    }
    std::sort(ordered.begin(), ordered.end(),
              [](const QPair<int, KisNode *> &a, const QPair<int, KisNode *> &b) {
                  return a.first < b.first;
              });
    KisNode *root = m_document->rootLayer().data();
    for (const auto &item : ordered) {
        const int idx = indexOfNode(item.second);
        if (idx <= 0 || idx >= m_layers.size() || !m_layers[idx].node) continue;
        KisNode *parentNode = m_layers[idx].node->parent().data();
        QString parentUuid;
        if (parentNode && parentNode != root) {
            const int pIdx = indexOfNode(parentNode);
            if (pIdx > 0 && pIdx < m_layers.size()) {
                parentUuid = m_layers[pIdx].uuid;
            }
        }
        m_collab->emitLayerOp(CollabLayerMoveRelative, idx, parentUuid, 0.0, 0);
    }
}

void ReverieCore::collabEmitLayerTreeAdded(int rootIndex)
{
    if (!m_collabActive || m_collabSuppressEmit || !m_collab || !m_document) return;
    if (rootIndex <= 0 || rootIndex >= m_layers.size() || !m_layers[rootIndex].node) return;
    const int baseDepth = m_layers[rootIndex].depth;
    int spanEnd = rootIndex + 1;
    if (m_layers[rootIndex].isGroup) {
        while (spanEnd < m_layers.size() && m_layers[spanEnd].depth > baseDepth) {
            ++spanEnd;
        }
    }
    // 包含紧邻挂载在同一深度的复制透明度蒙版
    while (spanEnd < m_layers.size() &&
           m_layers[spanEnd].depth == baseDepth &&
           m_layers[spanEnd].nodeType == NodeTypeTransparencyMask) {
        ++spanEnd;
    }

    QVector<KisNode *> nodes;
    nodes.reserve(spanEnd - rootIndex);
    for (int i = rootIndex; i < spanEnd; ++i) {
        if (m_layers[i].node) nodes.append(m_layers[i].node);
    }

    for (KisNode *n : nodes) {
        const int idx = indexOfNode(n);
        if (idx <= 0 || idx >= m_layers.size()) continue;
        const LayerEntry &e = m_layers[idx];
        if (e.isGroup) {
            collabEmitLayerOp(CollabLayerAddGroup, idx, e.name);
        } else if (e.nodeType == NodeTypeFill) {
            collabEmitLayerOp(CollabLayerAddFill, idx, e.name, 0.0, static_cast<int>(getFillLayerColor(idx)));
        } else if (e.nodeType == NodeTypeTransparencyMask) {
            collabEmitLayerOp(CollabLayerAddMask, idx, e.name);
        } else {
            collabEmitLayerOp(CollabLayerAddPaint, idx, e.name);
        }
        // 非 mask 节点按真实树位置补齐一次 MoveRelative, 确保跨组复制/嵌套严格对齐
        if (e.nodeType != NodeTypeTransparencyMask) {
            collabEmitLayerMoved({n});
        }
        if (!e.visible) collabEmitLayerOp(CollabLayerVisible, idx, QString(), 0.0, 0);
        const qreal op = layerOpacity(idx);
        if (op < 0.998) collabEmitLayerOp(CollabLayerOpacity, idx, QString(), op, 0);
        const QString blend = layerBlendMode(idx);
        if (!blend.isEmpty() && blend != QStringLiteral("normal")) {
            collabEmitLayerOp(CollabLayerBlend, idx, blend);
        }
        if (e.alphaLocked) collabEmitLayerOp(CollabLayerAlphaLocked, idx, QString(), 0.0, 1);
        if (layerClipped(idx)) collabEmitLayerOp(CollabLayerClipped, idx, QString(), 0.0, 1);
        if (layerAlphaInherited(idx)) collabEmitLayerOp(CollabLayerAlphaInherited, idx, QString(), 0.0, 1);
        if (e.colorLabel != 0) collabEmitLayerOp(CollabLayerColorLabel, idx, QString(), 0.0, e.colorLabel);
        if (e.isGroup && groupPassThrough(idx)) {
            collabEmitLayerOp(CollabLayerGroupPassThrough, idx, QString(), 0.0, 1);
        }
        if (!e.isGroup && e.nodeType != NodeTypeFill) {
            collabEmitLayerPixelsInitial(idx);
        }
    }
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

bool ReverieCore::collabCanEditLayerIndex(int index) const
{
    if (!m_collabActive) return true;
    return collabCanEditLayer(index);
}

KisPaintDeviceSP ReverieCore::collabSnapshotLayerBefore(int layerIndex) const
{
    if (!m_collabActive || m_collabSuppressEmit || !m_document) return KisPaintDeviceSP();
    if (layerIndex < 0 || layerIndex >= m_layers.size()) return KisPaintDeviceSP();
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[layerIndex]);
    if (!dev) return KisPaintDeviceSP();
    return KisPaintDeviceSP(new KisPaintDevice(*dev));
}

void ReverieCore::collabRecordLayerPixelEdit(int layerIndex,
                                             const KisPaintDeviceSP &beforeDev,
                                             const QRect &hintRect,
                                             bool isEraserLike)
{
    if (!m_collabActive || m_collabSuppressEmit || !m_collab || !beforeDev) return;
    m_collab->recordLocalToolBatch({qMakePair(layerIndex, beforeDev)}, {hintRect}, isEraserLike);
}

void ReverieCore::collabRecordLayerPixelBatch(const QVector<QPair<int, KisPaintDeviceSP>> &beforeList,
                                              const QVector<QRect> &hintRects,
                                              bool isEraserLike)
{
    if (!m_collabActive || m_collabSuppressEmit || !m_collab || beforeList.isEmpty()) return;
    m_collab->recordLocalToolBatch(beforeList, hintRects, isEraserLike);
}

void ReverieCore::finishPixelTransaction(KisTransaction &txn)
{
    if (m_document && m_undoCaptureEnabled) {
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    } else {
        KUndo2Command *cmd = txn.endAndTake();
        delete cmd;
    }
}

void ReverieCore::finishPixelTransactionPtr(KisTransaction *txn)
{
    if (!txn) return;
    if (m_document && m_undoCaptureEnabled) {
        txn->commit(m_document->undoAdapter());
        m_redoCount = 0;
    } else {
        KUndo2Command *cmd = txn->endAndTake();
        delete cmd;
    }
    delete txn;
}
