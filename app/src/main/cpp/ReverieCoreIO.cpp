/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreIO.cpp - File I/O: PSD save/load, thumbnails, project serialization
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include <QXmlStreamReader>
#include <QXmlStreamWriter>
#include <QDomDocument>
#include <QDomElement>
#include <QStack>
#include <vector>
#include <utility>
#include <functional>
#include <kis_store_paintdevice_writer.h>
#include <kis_group_layer.h>
#include <kis_paint_layer.h>
#include <QUuid>
#include <QBuffer>
#include <QThread>
#include <QtConcurrent/QtConcurrentMap>

void ReverieCore::setAuthorProfile(const QString &jsonStr)
{
    m_authorProfile = AuthorProfile();
    if (jsonStr.trimmed().isEmpty()) {
        return;
    }
    QJsonDocument doc = QJsonDocument::fromJson(jsonStr.toUtf8());
    if (!doc.isObject()) {
        return;
    }
    QJsonObject obj = doc.object();
    m_authorProfile.enabled = obj.value("enabled").toBool(true);
    m_authorProfile.name = obj.value("name").toString();
    m_authorProfile.nickname = obj.value("nickname").toString();
    m_authorProfile.organization = obj.value("organization").toString();
    m_authorProfile.email = obj.value("email").toString();
    m_authorProfile.website = obj.value("website").toString();
    m_authorProfile.copyright = obj.value("copyright").toString();
}

bool ReverieCore::savePng(const QString &path)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }
    QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull()) {
        return false;
    }
    if (m_authorProfile.enabled && !m_authorProfile.isEmpty()) {
        QString author = m_authorProfile.name.trimmed();
        if (author.isEmpty()) author = m_authorProfile.nickname.trimmed();
        if (!author.isEmpty()) {
            img.setText("Author", author);
            img.setText("Artist", author);
        }
        if (!m_authorProfile.copyright.trimmed().isEmpty()) {
            img.setText("Copyright", m_authorProfile.copyright.trimmed());
        }
        if (!m_authorProfile.organization.trimmed().isEmpty()) {
            img.setText("Organization", m_authorProfile.organization.trimmed());
        }
        QString contact;
        if (!m_authorProfile.website.trimmed().isEmpty()) contact += m_authorProfile.website.trimmed();
        if (!m_authorProfile.email.trimmed().isEmpty()) {
            if (!contact.isEmpty()) contact += " | ";
            contact += m_authorProfile.email.trimmed();
        }
        if (!contact.isEmpty()) {
            img.setText("Contact", contact);
        }
        img.setText("Software", "ReveriePaint");
        img.setText("Creation Time", QDateTime::currentDateTime().toString(Qt::ISODate));
    }
    return img.save(path, "PNG");
}

bool ReverieCore::exportJpg(const QString &path, int quality)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull()) {
        return false;
    }
    // Solid background for JPEG (composite on white if has transparent regions)
    QImage rgbImg(img.size(), QImage::Format_RGB32);
    rgbImg.fill(Qt::white);
    QPainter p(&rgbImg);
    p.drawImage(0, 0, img);
    p.end();
    if (m_authorProfile.enabled && !m_authorProfile.isEmpty()) {
        QString author = m_authorProfile.name.trimmed();
        if (author.isEmpty()) author = m_authorProfile.nickname.trimmed();
        if (!author.isEmpty()) {
            rgbImg.setText("Author", author);
            rgbImg.setText("Artist", author);
        }
        if (!m_authorProfile.copyright.trimmed().isEmpty()) {
            rgbImg.setText("Copyright", m_authorProfile.copyright.trimmed());
        }
        rgbImg.setText("Software", "ReveriePaint");
    }
    return rgbImg.save(path, "JPEG", qBound(1, quality, 100));
}

bool ReverieCore::exportPsd(const QString &path)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly)) {
        return false;
    }

    const bool haveLayers = m_layers.size() > 1;

    // 1. Header
    PSDHeader header;
    header.signature = "8BPS";
    header.version = 1;
    header.nChannels = haveLayers ? 4 : 3;
    header.width = image->width();
    header.height = image->height();
    header.colormode = RGB;
    header.channelDepth = 8;

    if (!header.write(file)) {
        return false;
    }

    // 2. Color mode block
    PSDColorModeBlock colorModeBlock(header.colormode);
    if (!colorModeBlock.write(file)) {
        return false;
    }

    // 3. Image resources section
    PSDImageResourceSection resourceSection;
    {
        RESN_INFO_1005 *resInfo = new RESN_INFO_1005;
        const qreal xRes = image->xRes() > 0 ? (image->xRes() * 72.0) : 72.0;
        const qreal yRes = image->yRes() > 0 ? (image->yRes() * 72.0) : 72.0;
        resInfo->hRes = xRes;
        resInfo->vRes = yRes;
        PSDResourceBlock *block = new PSDResourceBlock;
        block->identifier = PSDImageResourceSection::RESN_INFO;
        block->resource = resInfo;
        resourceSection.resources[PSDImageResourceSection::RESN_INFO] = block;
    }
    const bool resourceOk = resourceSection.write(file);
    delete resourceSection.resources.take(PSDImageResourceSection::RESN_INFO);
    if (!resourceOk) {
        return false;
    }

    // 4. Layer & mask section
    if (haveLayers && image->rootLayer()) {
        for (int i = 0; i < m_layers.size(); ++i) {
            const LayerEntry &e = m_layers[i];
            if (e.node) {
                e.node->setVisible(e.visible);
                e.node->setOpacity(qBound(0, int(layerOpacity(i) * 255.0 + 0.5), 255));
                QString blend = layerBlendMode(i).trimmed();
                if (!blend.isEmpty()) {
                    e.node->setCompositeOpId(blend);
                }
            }
        }
        PSDLayerMaskSection layerSection(header);
        layerSection.hasTransparency = true;
        if (!layerSection.write(file, image->rootLayer(), psd_compression_type::RLE)) {
            return false;
        }
    } else {
        psdwrite(file, (quint32)0);
    }

    // 5. Image data (merged projection composite)
    PSDImageData imagedata(&header);
    if (!imagedata.write(file, image->projection(), haveLayers, psd_compression_type::RLE)) {
        return false;
    }

    return true;
}

#include <thread>
#include <atomic>

namespace {

// 上一次 .revp 保存的阶段耗时与产物体积, 供 UI 侧"性能标尺"(PerfTrace HUD) 显示 ——
// 保存慢时必须能一眼看出慢在快照、PNG 编码还是写盘, 否则只能盲改。
// 写入方可能是引擎线程(同步保存)或写盘线程(异步保存), 读取方是 UI 线程 ⇒ 用原子量。
enum RevpStat {
    StatTotal = 0,   // 整个 saveRevp 的墙钟
    StatSnapshot,    // 引擎线程: 元数据/XML + 预览转换 + 各图层与关键帧快照
    StatEncode,      // 工作线程: readBytes + 色彩转换 + PNG 编码
    StatWrite,       // 写盘: zip deflate + 文件写入 + 关闭改名
    StatPngCount,    // PNG 条目数
    StatPngBytes,    // PNG 字节总量
    StatFileBytes,   // 最终 .revp 体积
    StatAsync,       // 1 = 异步保存
    RevpStatCount
};

std::atomic<qint64> s_revpStats[RevpStatCount];

void publishRevpStats(qint64 totalMs, qint64 snapshotMs, qint64 encodeNs, qint64 writeNs,
                      qint64 pngCount, qint64 pngBytes, qint64 fileBytes, bool async)
{
    s_revpStats[StatTotal].store(totalMs, std::memory_order_relaxed);
    s_revpStats[StatSnapshot].store(snapshotMs, std::memory_order_relaxed);
    s_revpStats[StatEncode].store(encodeNs / 1000000, std::memory_order_relaxed);
    s_revpStats[StatWrite].store(writeNs / 1000000, std::memory_order_relaxed);
    s_revpStats[StatPngCount].store(pngCount, std::memory_order_relaxed);
    s_revpStats[StatPngBytes].store(pngBytes, std::memory_order_relaxed);
    s_revpStats[StatFileBytes].store(fileBytes, std::memory_order_relaxed);
    s_revpStats[StatAsync].store(async ? 1 : 0, std::memory_order_relaxed);
}

// 一轮 PNG 写入的累计统计 (编码 / 写盘耗时, 条目数与字节数)
struct RevpPngStats {
    qint64 encodeNs = 0;
    qint64 writeNs = 0;
    qint64 count = 0;
    qint64 bytes = 0;
};

// 图层 -> 栅格关键帧通道 (与 ReverieCoreAnimation.cpp 的 rasterChannelOf 同义,
// 这里自带一份避免跨翻译单元依赖)。create=true 即"开启动画", 幂等安全;
// 仅 KisPaintLayer 支持 Raster 通道, 其余图层类型返回空指针。
KisRasterKeyframeChannel *revpRasterChannel(KisNode *node, bool create)
{
    if (!node) return nullptr;
    if (!dynamic_cast<KisPaintLayer *>(node)) return nullptr;
    return dynamic_cast<KisRasterKeyframeChannel *>(
        node->getKeyframeChannel(KisKeyframeChannel::Raster.id(), create));
}

// ---------------------------------------------------------------------------
// .revp 容器内的 PNG 条目: 编码档位 + 并行编码
// ---------------------------------------------------------------------------
// 档位依据 scripts/native-bench 的宿主实测 (见 docs/RENDER-OPTIMIZATION.md §5.12):
// Qt 的 quality=-1 (默认) 等价于 q=30, 在噪声/厚涂型内容上恰好是**最慢**的一档
// (2048² 噪点图 1032ms/10.6MB), 而 q=70 只要 235ms/9.0MB —— 又快又小; 线稿型
// 内容 (透明底 + 笔迹) 393ms → 149ms, 体积 +7%。产物仍是标准 PNG, 旧版本照读,
// 新版本读到的像素与改动前逐字节一致 (PNG 无损)。
const int kRevpPngQualityDefault = 70;

int revpPngQuality()
{
    int q = kRevpPngQualityDefault;
    // 真机 A/B 用: setprop debug.reverie.pngq <1..89>
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.pngq", value) > 0 && value[0]) {
        q = QByteArray(value).toInt();
    }
#else
    const QByteArray env = qgetenv("REVERIE_PNGQ");
    if (!env.isEmpty()) q = env.toInt();
#endif
    // 90 以上 Qt 直接写"不压缩"的 PNG (体积暴涨到原始大小), 上界压在 89
    return qBound(1, q, 89);
}

QByteArray encodePngBytes(const QImage &img, int quality)
{
    QByteArray bytes;
    QBuffer buf(&bytes);
    buf.open(QIODevice::WriteOnly);
    if (!img.save(&buf, "PNG", quality)) {
        bytes.clear();
    }
    return bytes;
}

// 一个 PNG 条目, 二选一地携带数据源:
//   - img:     调用方已物化好的图 (预览 / 选区掩码这类"整份只有一张"的条目)
//   - produce: 在工作线程"现取现编码"的工厂
// 工厂的意义是**流式**: 图层/关键帧的整幅 QImage 不再全部常驻内存 —— 旧实现先给每个
// 图层造一张 ARGB32 (4096 画幅单张 64MB, 15 图层就是 960MB) 再统一编码, 大项目保存
// 的瓶颈根本不是 PNG 压缩, 而是这一坨分配与内存流量。改成按块"生成→编码→写盘→释放"。
// 工厂跑在工作线程上, 所以只能碰"该时刻不会被并发写"的数据: 同步保存时渲染线程正阻塞
// 在保存调用里; 异步保存用引擎线程克隆/复制出的快照设备 (见 appendSnapshotLayerJob)。
struct RevpPngJob {
    QString name;
    QImage img;                                     // 拥有的快照 (可为空)
    std::function<QByteArray(int quality)> produce; // 或: 工作线程现取现编码
};

// 并行编码 + 串行写入 (zip 条目只能按顺序写) + 逐块释放。
// 峰值内存 ≈ min(线程数, 条目数) × 单图 (Krita 转换临时缓冲 + QImage + PNG 字节),
// 与图层/关键帧总数无关。
void writeRevpPngJobs(KoStore *store, QVector<RevpPngJob> &jobs, RevpPngStats *stats = nullptr)
{
    const int total = int(jobs.size());
    if (total == 0) return;
    const int quality = revpPngQuality();
    QThreadPool *pool = reverieBackgroundPool();
    const int chunkSize = qMax(1, qMin(int(pool->maxThreadCount()), total));
    // 只读别名: 并行期间必须走 const operator[] (非 const 重载带 detach 检查, 并发即竞争)
    const QVector<RevpPngJob> &jobsRef = jobs;
    QElapsedTimer timer;
    for (int base = 0; base < total; base += chunkSize) {
        const int count = qMin(chunkSize, total - base);
        // 每个任务只写自己那一个槽位: std::vector 裸下标 (QVector<QByteArray> 同理不安全)
        std::vector<QByteArray> encoded(static_cast<size_t>(count));
        const auto encodeSlot = [&encoded, &jobsRef, base, quality](int slot) {
            const RevpPngJob &job = jobsRef[base + slot];
            if (job.produce) {
                encoded[size_t(slot)] = job.produce(quality);
            } else if (!job.img.isNull()) {
                encoded[size_t(slot)] = encodePngBytes(job.img, quality);
            }
        };
        if (stats) timer.start();
        if (count > 1) {
            QVector<int> order(count);
            for (int i = 0; i < count; ++i) order[i] = i;
            QtConcurrent::blockingMap(pool, order, [&encodeSlot](int &slot) { encodeSlot(slot); });
        } else {
            encodeSlot(0);
        }
        if (stats) stats->encodeNs += timer.nsecsElapsed();
        for (int i = 0; i < count; ++i) {
            // 写完立刻释放该条目的数据源 (QImage / 工厂捕获的快照设备)
            jobs[base + i].img = QImage();
            jobs[base + i].produce = nullptr;
            if (encoded[size_t(i)].isEmpty()) continue;
            if (stats) {
                stats->count++;
                stats->bytes += encoded[size_t(i)].size();
                timer.start();
            }
            // PNG 本身已是 deflate 流, 容器再压一遍纯粹白烧 CPU (体积几乎不变)
            store->setCompressionEnabled(false);
            if (store->open(jobsRef[base + i].name)) {
                store->write(encoded[size_t(i)]);
                store->close();
            }
            if (stats) stats->writeNs += timer.nsecsElapsed();
        }
    }
    // 后续条目 (meta / 资产 / 录制) 恢复正常压缩
    store->setCompressionEnabled(true);
}

// 已经是压缩格式的资源 (音频/视频/图片) 再塞进 zip 里 deflate 一遍, 体积几乎不变、
// 纯白烧 CPU; 大项目里这类资产可达几十 MB。只对确定已压缩的扩展名关闭容器压缩。
bool isPrecompressedAsset(const QString &fileName)
{
    const QString ext = fileName.section(QLatin1Char('.'), -1).toLower();
    static const char *kExts[] = {"mp4", "m4v", "mov", "mkv", "webm", "avi", "mp3", "m4a",
                                  "aac", "ogg", "opus", "flac", "jpg", "jpeg", "png", "gif",
                                  "webp", "zip", "gz", "apk"};
    for (const char *e : kExts) {
        if (ext == QLatin1String(e)) return true;
    }
    return false;
}

// 图层快照: 引擎线程上做瓦片级 COW 克隆 (makeCloneFrom → fastBitBlt 只搬指针),
// 真正昂贵的 readBytes + convertPixelsTo + PNG 编码留给工作线程并行做。
// 克隆而不是就地转换的原因见 RevpPngJob 注释 (异步保存时引擎线程仍在绘制)。
RevpPngJob makeSnapshotLayerJob(const QString &name, const KisPaintDeviceSP &dev, int docW, int docH)
{
    RevpPngJob job;
    job.name = name;
    if (!dev) return job;
    KisPaintDeviceSP snap = new KisPaintDevice(dev->colorSpace());
    snap->makeCloneFrom(dev, dev->exactBounds());
    job.produce = [snap, docW, docH](int quality) {
        QImage img = snap->convertToQImage(nullptr, 0, 0, docW, docH);
        if (img.isNull()) {
            img = QImage(docW, docH, QImage::Format_ARGB32_Premultiplied);
            img.fill(Qt::transparent);
        }
        return encodePngBytes(img, quality);
    };
    return job;
}

// 动画关键帧快照: writeToDevice 把该帧像素拷进独立设备 (引擎线程, 瓦片级),
// 转换 + 编码在工作线程。
RevpPngJob makeKeyframePngJob(const QString &name, KisRasterKeyframeChannel *ch,
                              const KisPaintDeviceSP &dev, int time, int docW, int docH)
{
    RevpPngJob job;
    job.name = name;
    if (!ch || !dev) return job;
    KisPaintDeviceSP frameDev = new KisPaintDevice(dev->colorSpace());
    ch->writeToDevice(time, frameDev);
    job.produce = [frameDev, docW, docH](int quality) {
        QImage img = frameDev->convertToQImage(nullptr, 0, 0, docW, docH);
        if (img.isNull()) return QByteArray();
        return encodePngBytes(img, quality);
    };
    return job;
}

bool writeRevpStore(const QString &path,
                    const QJsonObject &meta,
                    const QString &layersXml,
                    QVector<RevpPngJob> pngJobs,
                    const QMap<QString, QByteArray> &assets,
                    const QByteArray &recordingBlob,
                    QVector<RevpPngJob> selectionPngJobs,
                    RevpPngStats *outStats = nullptr)
{
    const QString tmpPath = path + ".tmp";
    QScopedPointer<KoStore> store(KoStore::createStore(tmpPath, KoStore::Write, "application/x-reveriepaint", KoStore::Zip));
    if (!store || store->bad()) {
        return false;
    }

    // 1. Meta / Manifest JSON
    if (store->open("meta.json")) {
        QJsonDocument doc(meta);
        QByteArray data = doc.toJson(QJsonDocument::Indented);
        store->write(data);
        store->close();
    }

    // layers.xml
    if (!layersXml.isEmpty()) {
        if (store->open("layers.xml")) {
            store->write(layersXml.toUtf8());
            store->close();
        }
    }

    // 2-4. 预览 / 缩略图 / 图层 / 动画关键帧: 调用方按流式顺序组织好的 PNG 条目表,
    // 并行编码 + 顺序写入 (条目顺序与逐条编码时完全一致)
    writeRevpPngJobs(store.data(), pngJobs, outStats);

    // 5. Imported assets (音频/视频等二进制资源, 文件名即资源名)
    for (auto it = assets.constBegin(); it != assets.constEnd(); ++it) {
        // 已压缩媒体不再做容器压缩 (大项目里这类资产可达几十 MB)
        store->setCompressionEnabled(!isPrecompressedAsset(it.key()));
        if (store->open("assets/" + it.key())) {
            store->write(it.value());
            store->close();
        }
    }
    store->setCompressionEnabled(true);

    // 5.5 Stored Selection masks (选区历史与存储槽位): 同样是 PNG 条目
    // (掩码读取在调用方的引擎线程完成, 这里的工厂只做编码)
    writeRevpPngJobs(store.data(), selectionPngJobs, outStats);

    // 6. Recording
    if (!recordingBlob.isEmpty()) {
        if (store->open("recording")) {
            store->write(recordingBlob);
            store->close();
        }
    }

    QElapsedTimer closeTimer;
    if (outStats) closeTimer.start();
    store.reset(); // flushes and closes zip

    QFile::remove(path);
    if (!QFile::rename(tmpPath, path)) {
        QFile::remove(path);
        QFile::copy(tmpPath, path);
        QFile::remove(tmpPath);
    }
    if (outStats) outStats->writeNs += closeTimer.nsecsElapsed();

    QFile f(path);
    return f.exists() && f.size() > 0;
}

static std::atomic<bool> s_savingRevpAsync{false};

} // namespace

bool ReverieCore::saveRevp(const QString &path, const QString &extraMetaJson, const QByteArray &recordingBlob)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }

    // 阶段计时: [保存开始, 调用 writeRevpStore) = 引擎线程上的快照工作 (元数据 + 预览
    // 转换 + 图层/关键帧快照 + 选区掩码读取); 之后的耗时由 writeRevpStore 回填编码/写盘
    const qint64 saveStartMs = QDateTime::currentMSecsSinceEpoch();

    syncLayersFromImage();

    // 1. Meta / Manifest JSON
    QJsonObject meta;
    meta["version"] = 1;
    meta["appName"] = "ReveriePaint";
    meta["width"] = image->width();
    meta["height"] = image->height();
    meta["colorMode"] = "RGB";
    meta["colorDepth"] = 8;
    meta["xRes"] = image->xRes();
    meta["yRes"] = image->yRes();
    meta["createdTime"] = QDateTime::currentDateTime().toString(Qt::ISODate);
    meta["modifiedTime"] = QDateTime::currentDateTime().toString(Qt::ISODate);

    // Merge in extra metadata passed from Java/Kotlin (stroke count, draw duration, etc.)
    if (!extraMetaJson.isEmpty()) {
        QJsonDocument extraDoc = QJsonDocument::fromJson(extraMetaJson.toUtf8());
        if (extraDoc.isObject()) {
            QJsonObject extraObj = extraDoc.object();
            for (auto it = extraObj.begin(); it != extraObj.end(); ++it) {
                meta[it.key()] = it.value();
            }
        }
    }

    // Author metadata
    if (!meta.contains("author") && m_authorProfile.enabled && !m_authorProfile.isEmpty()) {
        QJsonObject authorObj;
        authorObj["name"] = m_authorProfile.name;
        authorObj["nickname"] = m_authorProfile.nickname;
        authorObj["organization"] = m_authorProfile.organization;
        authorObj["email"] = m_authorProfile.email;
        authorObj["website"] = m_authorProfile.website;
        authorObj["copyright"] = m_authorProfile.copyright;
        meta["author"] = authorObj;
    }

    // Layer metadata array
    QJsonArray layersArray;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        QJsonObject layerObj;
        layerObj["index"] = i;
        layerObj["name"] = e.name;
        layerObj["visible"] = e.visible;
        layerObj["opacity"] = layerOpacity(i);
        layerObj["blendMode"] = layerBlendMode(i);
        layerObj["locked"] = e.locked;
        layerObj["alphaLocked"] = e.alphaLocked;
        layerObj["clipped"] = e.clipped;
        layerObj["isGroup"] = e.isGroup;
        layerObj["depth"] = e.depth;
        layerObj["colorLabel"] = e.colorLabel;
        layerObj["background"] = e.background;

        // 动画轨道: 记录关键帧时间列表 (时间轴画廊标识也依赖它)
        KisRasterKeyframeChannel *kfCh = revpRasterChannel(e.node, false);
        if (kfCh) {
            QList<int> times = kfCh->allKeyframeTimes().values();
            std::sort(times.begin(), times.end());
            if (!times.isEmpty()) {
                layerObj["animated"] = true;
                QJsonArray kArr;
                for (int t : times) kArr.append(t);
                layerObj["keyframes"] = kArr;
            }
        }
        // 洋葱皮开关: per-paint-layer 属性, 单独持久化 (重进画布后需还原)
        if (const KisPaintLayer *pl = dynamic_cast<const KisPaintLayer *>(e.node)) {
            // 抑制窗口 (播放) 里 node property 已被压掉, 序列化走逻辑状态,
            // 否则播放中的 autosave 会把各层洋葱皮永久存成 false
            if (onionSkinLogicalEnabled(pl)) {
                layerObj["onionskin"] = true;
            }
        }
        layersArray.append(layerObj);
    }
    meta["layers"] = layersArray;

    // 动画元信息 (以引擎为唯一真身; 无动画文档这里只有默认帧率, 无害)
    {
        QJsonObject animObj;
        animObj["framerate"] = animationFramerate();
        int pbStart = 0;
        int pbEnd = 0;
        animationPlaybackRange(&pbStart, &pbEnd);
        animObj["playbackStart"] = pbStart;
        animObj["playbackEnd"] = pbEnd;
        animObj["currentTime"] = animationCurrentTime();

        // 关键帧色标与末帧保持时长
        QJsonArray tagsArr;
        for (auto it = m_keyframeTags.constBegin(); it != m_keyframeTags.constEnd(); ++it) {
            QJsonObject tagObj;
            tagObj["layer"] = int(quint32(it.key() >> 32));
            tagObj["time"] = int(quint32(it.key() & 0xFFFFFFFFULL));
            tagObj["tag"] = it.value();
            tagsArr.append(tagObj);
        }
        animObj["keyframeTags"] = tagsArr;

        QJsonObject holdsObj;
        for (auto it = m_lastFrameHold.constBegin(); it != m_lastFrameHold.constEnd(); ++it) {
            holdsObj[QString::number(it.key())] = it.value();
        }
        animObj["lastFrameHolds"] = holdsObj;

        meta["animation"] = animObj;
    }

    QString xml;
    writeLayersXml(&xml);

    // 预览/缩略图: 只有这一项需要整幅物化 (缩略图要从它缩放), 其余条目全部流式
    const QImage comp = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    const int docW = image->width();
    const int docH = image->height();

    QVector<RevpPngJob> pngJobs;
    pngJobs.reserve(m_layers.size() + 2);
    if (!comp.isNull()) {
        RevpPngJob preview;
        preview.name = QStringLiteral("preview.png");
        preview.img = comp; // 共享引用 (不复制像素), 写完即释放
        pngJobs.append(std::move(preview));

        RevpPngJob thumb;
        thumb.name = QStringLiteral("thumbnail.png");
        thumb.produce = [comp](int quality) {
            const QImage t = comp.scaled(400, 400, Qt::KeepAspectRatio, Qt::SmoothTransformation);
            return encodePngBytes(t, quality);
        };
        pngJobs.append(std::move(thumb));
    }

    // 图层: 引擎线程只做瓦片级克隆 (指针拷贝), readBytes + 色彩转换 + PNG 编码都在
    // 工作线程并行完成 —— 这才是大项目保存耗时的大头 (旧实现: 每层先分配并常驻
    // 一整幅 ARGB32, 4096 画幅 15 图层就是 960MB, 然后才逐个编码)。
    // 同步保存期间渲染线程正阻塞在本函数里, 图层设备不会被并发写。
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;
        pngJobs.append(makeSnapshotLayerJob(
            QString("layer_%1.png").arg(i, 3, 10, QChar('0')), dev, docW, docH));
    }

    // 动画关键帧画面: 每个动画图层的每个关键帧整幅画布导出 (同样流式)
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisRasterKeyframeChannel *kfCh = revpRasterChannel(e.node, false);
        if (!kfCh) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;
        QList<int> times = kfCh->allKeyframeTimes().values();
        std::sort(times.begin(), times.end());
        for (int t : times) {
            pngJobs.append(makeKeyframePngJob(
                QString("frame_%1_%2.png").arg(i, 3, 10, QChar('0')).arg(t, 5, 10, QChar('0')),
                kfCh, dev, t, docW, docH));
        }
    }

    QJsonArray assetNames;
    for (auto it = m_revAssets.constBegin(); it != m_revAssets.constEnd(); ++it) {
        assetNames.append(it.key());
    }
    if (!assetNames.isEmpty()) {
        meta["assets"] = assetNames;
    }

    QVector<RevpPngJob> selectionPngJobs;
    if (!m_storedSelections.isEmpty()) {
        QJsonArray selArr;
        for (int i = 0; i < m_storedSelections.size(); ++i) {
            const StoredSelection &item = m_storedSelections[i];
            const QString fileName = QString("selections/selection_%1.png").arg(i, 3, 10, QChar('0'));
            QJsonObject sObj;
            sObj["id"] = item.id;
            sObj["name"] = item.name;
            sObj["file"] = fileName;
            selArr.append(sObj);

            if (item.selection) {
                // 掩码读取必须在引擎线程 (选区设备), PNG 编码交给工作线程
                const QVector<quint8> mask = readSelectionMaskBytes(image, item.selection);
                QImage mImg(image->width(), image->height(), QImage::Format_Grayscale8);
                for (int y = 0; y < image->height(); ++y) {
                    memcpy(mImg.scanLine(y), mask.constData() + size_t(y) * image->width(), image->width());
                }
                RevpPngJob job;
                job.name = fileName;
                job.img = mImg;
                selectionPngJobs.append(std::move(job));
            }
        }
        meta["storedSelections"] = selArr;
    }

    const qint64 snapshotMs = QDateTime::currentMSecsSinceEpoch() - saveStartMs;
    RevpPngStats pngStats;
    const bool ok = writeRevpStore(path, meta, xml, std::move(pngJobs), m_revAssets, recordingBlob,
                                   std::move(selectionPngJobs), &pngStats);
    QFile out(path);
    publishRevpStats(QDateTime::currentMSecsSinceEpoch() - saveStartMs, snapshotMs,
                     pngStats.encodeNs, pngStats.writeNs, pngStats.count, pngStats.bytes,
                     out.exists() ? out.size() : 0, false);
    return ok;
}

bool ReverieCore::saveRevpAsync(const QString &path, const QString &extraMetaJson, const QByteArray &recordingBlob)
{
    if (s_savingRevpAsync.load()) {
        qDebug() << "saveRevpAsync: previous async save still running, skip";
        return false;
    }

    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }

    const qint64 saveStartMs = QDateTime::currentMSecsSinceEpoch();

    syncLayersFromImage();

    // 1. Meta / Manifest JSON
    QJsonObject meta;
    meta["version"] = 1;
    meta["appName"] = "ReveriePaint";
    meta["width"] = image->width();
    meta["height"] = image->height();
    meta["colorMode"] = "RGB";
    meta["colorDepth"] = 8;
    meta["xRes"] = image->xRes();
    meta["yRes"] = image->yRes();
    meta["createdTime"] = QDateTime::currentDateTime().toString(Qt::ISODate);
    meta["modifiedTime"] = QDateTime::currentDateTime().toString(Qt::ISODate);

    if (!extraMetaJson.isEmpty()) {
        QJsonDocument extraDoc = QJsonDocument::fromJson(extraMetaJson.toUtf8());
        if (extraDoc.isObject()) {
            QJsonObject extraObj = extraDoc.object();
            for (auto it = extraObj.begin(); it != extraObj.end(); ++it) {
                meta[it.key()] = it.value();
            }
        }
    }

    QJsonArray layersArray;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        QJsonObject layerObj;
        layerObj["index"] = i;
        layerObj["name"] = e.name;
        layerObj["visible"] = e.visible;
        layerObj["opacity"] = layerOpacity(i);
        layerObj["blendMode"] = layerBlendMode(i);
        layerObj["locked"] = e.locked;
        layerObj["alphaLocked"] = e.alphaLocked;
        layerObj["clipped"] = e.clipped;
        layerObj["isGroup"] = e.isGroup;
        layerObj["depth"] = e.depth;
        layerObj["colorLabel"] = e.colorLabel;
        layerObj["background"] = e.background;

        // 动画轨道: 关键帧时间列表
        KisRasterKeyframeChannel *kfCh = revpRasterChannel(e.node, false);
        if (kfCh) {
            QList<int> times = kfCh->allKeyframeTimes().values();
            std::sort(times.begin(), times.end());
            if (!times.isEmpty()) {
                layerObj["animated"] = true;
                QJsonArray kArr;
                for (int t : times) kArr.append(t);
                layerObj["keyframes"] = kArr;
            }
        }
        // 洋葱皮开关: per-paint-layer 属性, 单独持久化 (重进画布后需还原)
        if (const KisPaintLayer *pl = dynamic_cast<const KisPaintLayer *>(e.node)) {
            // 抑制窗口 (播放) 里 node property 已被压掉, 序列化走逻辑状态,
            // 否则播放中的 autosave 会把各层洋葱皮永久存成 false
            if (onionSkinLogicalEnabled(pl)) {
                layerObj["onionskin"] = true;
            }
        }
        layersArray.append(layerObj);
    }
    meta["layers"] = layersArray;

    // 动画元信息 (引擎为真身)
    {
        QJsonObject animObj;
        animObj["framerate"] = animationFramerate();
        int pbStart = 0;
        int pbEnd = 0;
        animationPlaybackRange(&pbStart, &pbEnd);
        animObj["playbackStart"] = pbStart;
        animObj["playbackEnd"] = pbEnd;
        animObj["currentTime"] = animationCurrentTime();

        // 关键帧色标与末帧保持时长
        QJsonArray tagsArr;
        for (auto it = m_keyframeTags.constBegin(); it != m_keyframeTags.constEnd(); ++it) {
            QJsonObject tagObj;
            tagObj["layer"] = int(quint32(it.key() >> 32));
            tagObj["time"] = int(quint32(it.key() & 0xFFFFFFFFULL));
            tagObj["tag"] = it.value();
            tagsArr.append(tagObj);
        }
        animObj["keyframeTags"] = tagsArr;

        QJsonObject holdsObj;
        for (auto it = m_lastFrameHold.constBegin(); it != m_lastFrameHold.constEnd(); ++it) {
            holdsObj[QString::number(it.key())] = it.value();
        }
        animObj["lastFrameHolds"] = holdsObj;

        meta["animation"] = animObj;
    }

    QString xml;
    writeLayersXml(&xml);

    // 图层/关键帧快照都在引擎线程上"只搬指针", 昂贵的整幅转换与 PNG 编码全部放进写盘
    // 线程并行做。旧实现在引擎线程上逐层 convertToQImage **再 deep copy 一份**
    // (整幅 64MB × 2 × 图层数), 自动保存时正是它把绘制拖住; 而且所有图层的整幅图同时
    // 常驻 (4096 画幅 15 层 ≈ 960MB), 大项目下这比 PNG 压缩贵得多。
    // 注: convertToQImage 返回的 QImage 已经是独立分配的新图, 旧代码的 .copy() 纯属
    // 多余的一次整幅拷贝 —— 这里连同内存一起省掉。
    const QImage comp = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    const int docW = image->width();
    const int docH = image->height();

    QVector<RevpPngJob> pngJobs;
    pngJobs.reserve(m_layers.size() + 2);
    if (!comp.isNull()) {
        RevpPngJob preview;
        preview.name = QStringLiteral("preview.png");
        preview.img = comp;
        pngJobs.append(std::move(preview));

        RevpPngJob thumb;
        thumb.name = QStringLiteral("thumbnail.png");
        thumb.produce = [comp](int quality) {
            const QImage t = comp.scaled(400, 400, Qt::KeepAspectRatio, Qt::SmoothTransformation);
            return encodePngBytes(t, quality);
        };
        pngJobs.append(std::move(thumb));
    }

    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;
        pngJobs.append(makeSnapshotLayerJob(
            QString("layer_%1.png").arg(i, 3, 10, QChar('0')), dev, docW, docH));
    }

    // 关键帧: 同样只做瓦片拷贝 (writeToDevice 到独立设备), 转换 + 编码在写盘线程
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisRasterKeyframeChannel *kfCh = revpRasterChannel(e.node, false);
        if (!kfCh) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;
        QList<int> times = kfCh->allKeyframeTimes().values();
        std::sort(times.begin(), times.end());
        for (int t : times) {
            pngJobs.append(makeKeyframePngJob(
                QString("frame_%1_%2.png").arg(i, 3, 10, QChar('0')).arg(t, 5, 10, QChar('0')),
                kfCh, dev, t, docW, docH));
        }
    }

    QJsonArray assetNames;
    for (auto it = m_revAssets.constBegin(); it != m_revAssets.constEnd(); ++it) {
        assetNames.append(it.key());
    }
    if (!assetNames.isEmpty()) {
        meta["assets"] = assetNames;
    }
    const QMap<QString, QByteArray> assetsCopy = m_revAssets;

    QVector<RevpPngJob> selectionPngJobs;
    if (!m_storedSelections.isEmpty()) {
        QJsonArray selArr;
        for (int i = 0; i < m_storedSelections.size(); ++i) {
            const StoredSelection &item = m_storedSelections[i];
            const QString fileName = QString("selections/selection_%1.png").arg(i, 3, 10, QChar('0'));
            QJsonObject sObj;
            sObj["id"] = item.id;
            sObj["name"] = item.name;
            sObj["file"] = fileName;
            selArr.append(sObj);

            if (item.selection) {
                // 掩码读取必须在引擎线程 (选区设备), PNG 编码交给写盘线程
                const QVector<quint8> mask = readSelectionMaskBytes(image, item.selection);
                QImage mImg(image->width(), image->height(), QImage::Format_Grayscale8);
                for (int y = 0; y < image->height(); ++y) {
                    memcpy(mImg.scanLine(y), mask.constData() + size_t(y) * image->width(), image->width());
                }
                RevpPngJob job;
                job.name = fileName;
                job.img = mImg;
                selectionPngJobs.append(std::move(job));
            }
        }
        meta["storedSelections"] = selArr;
    }

    const qint64 snapshotMs = QDateTime::currentMSecsSinceEpoch() - saveStartMs;
    s_savingRevpAsync.store(true);
    // mutable: 任务表要按值 move 进线程体 (否则 std::move 退化成浅拷贝)
    std::thread([path, meta, xml, pngJobs = std::move(pngJobs), assetsCopy, recordingBlob,
                 selectionPngJobs = std::move(selectionPngJobs), saveStartMs,
                 snapshotMs]() mutable {
        RevpPngStats pngStats;
        const bool ok = writeRevpStore(path, meta, xml, std::move(pngJobs), assetsCopy,
                                       recordingBlob, std::move(selectionPngJobs), &pngStats);
        QFile out(path);
        publishRevpStats(QDateTime::currentMSecsSinceEpoch() - saveStartMs, snapshotMs,
                         pngStats.encodeNs, pngStats.writeNs, pngStats.count, pngStats.bytes,
                         out.exists() ? out.size() : 0, true);
        s_savingRevpAsync.store(false);
        qDebug() << "saveRevpAsync finished, result=" << ok << "path=" << path;
    }).detach();

    return true;
}

void ReverieCore::revpSaveStats(qint64 *out)
{
    if (!out) return;
    for (int i = 0; i < RevpStatCount; ++i) {
        out[i] = s_revpStats[i].load(std::memory_order_relaxed);
    }
}

static QByteArray readAllStoreBytes(KoStore *store)
{
    QByteArray data;
    const qint64 total = store->size();
    if (total > 0) {
        data = store->read(total);
        if (data.size() == total) {
            return data;
        }
    }
    // Fallback: read in chunks up to 64MB if size is -1, 0, or incomplete
    char buf[65536];
    while (true) {
        qint64 n = store->read(buf, sizeof(buf));
        if (n <= 0) break;
        data.append(buf, int(n));
        if (data.size() > 64 * 1024 * 1024) break;
    }
    return data;
}

static void paintDeviceFromPng(KisPaintDeviceSP dev, const QByteArray &pngBytes)
{
    if (!dev || pngBytes.isEmpty()) return;
    QImage img;
    if (!img.loadFromData(pngBytes, "PNG")) return;
    dev->clear();
    dev->convertFromQImage(img, nullptr);
    dev->setDirty();
}

static bool loadLayerDataFromStore(KoStore *store, KisPaintDeviceSP dev, const QString &docName, const QString &filename, int index)
{
    if (!store || !dev) return false;

    QStringList candidates;
    if (!docName.isEmpty() && !filename.isEmpty()) {
        candidates << QString("%1/layers/%2").arg(docName, filename);
        candidates << QString("%1/layers/%2.png").arg(docName, filename);
    }
    if (!filename.isEmpty()) {
        candidates << QString("layers/%1").arg(filename);
        candidates << QString("layers/%1.png").arg(filename);
        candidates << filename;
        candidates << QString("%1.png").arg(filename);
    }
    candidates << QString("layer_%1.png").arg(index, 3, 10, QChar('0'));
    candidates << QString("layer%1.png").arg(index);

    const QStringList dirList = store->directoryList();
    for (const QString &d : dirList) {
        if (!d.isEmpty() && d != docName && !filename.isEmpty()) {
            candidates << QString("%1/layers/%2").arg(d, filename);
            candidates << QString("%1/layers/%2.png").arg(d, filename);
        }
    }

    for (const QString &cand : candidates) {
        if (store->open(cand)) {
            QByteArray data = readAllStoreBytes(store);
            store->close();
            if (data.isEmpty()) {
                continue;
            }
            if (data.size() >= 8 && memcmp(data.constData(), "\x89PNG\r\n\x1a\n", 8) == 0) {
                paintDeviceFromPng(dev, data);
            } else {
                QBuffer buf(&data);
                buf.open(QIODevice::ReadOnly);
                if (dev->read(&buf)) {
                    dev->setDirty();
                } else {
                    qWarning() << "loadLayerDataFromStore: dev->read failed for" << cand;
                    continue;
                }
            }

            // Check if defaultpixel exists
            if (store->open(cand + ".defaultpixel")) {
                const int pxSize = dev->colorSpace()->pixelSize();
                QByteArray dp = readAllStoreBytes(store);
                store->close();
                if (dp.size() == pxSize) {
                    KoColor defColor(reinterpret_cast<const quint8*>(dp.constData()), dev->colorSpace());
                    dev->setDefaultPixel(defColor);
                }
            }
            return true;
        }
    }
    return false;
}

static bool loadKraNodesDom(const QDomElement &parentElem,
                            KisImageSP image,
                            KisNodeSP parentNode,
                            KoStore *store,
                            const QString &docName,
                            int &layerIndexCounter,
                            bool *bgVisible)
{
    if (parentElem.isNull() || !image || !parentNode || !store) return false;

    QVector<QDomElement> layerElements;
    for (QDomElement child = parentElem.firstChildElement(); !child.isNull(); child = child.nextSiblingElement()) {
        const QString tag = child.tagName().toLower();
        if (tag == "layer" || tag == "mask") {
            layerElements.append(child);
        }
    }

    const KoColorSpace *cs = image->colorSpace();
    bool any = false;

    // Bottom-to-top traversal: in maindoc.xml, layers are listed top-to-bottom.
    // Iterating in reverse adds bottom-most layers first into parentNode.
    for (int i = layerElements.size() - 1; i >= 0; --i) {
        const QDomElement &el = layerElements[i];
        const QString nodeType = el.attribute("nodetype", el.attribute("layertype", "paintlayer")).toLower();
        QString name = el.attribute("name");
        if (name.isEmpty()) {
            name = (nodeType == "grouplayer") ? QStringLiteral("图层组") : QStringLiteral("图层");
        }
        const int opacity = qBound(0, el.attribute("opacity", "255").toInt(), 255);
        KisNodeSP node;
        const bool isGroup = (nodeType == "grouplayer");

        if (isGroup) {
            node = new KisGroupLayer(image, name, opacity, cs);
        } else {
            KisPaintLayerSP pl = new KisPaintLayer(image, name, opacity, cs);
            const QString fn = el.attribute("filename");
            loadLayerDataFromStore(store, pl->paintDevice(), docName, fn, layerIndexCounter++);
            node = pl;
        }

        if (node) {
            const bool visible = el.attribute("visible", "1") != "0";
            node->setVisible(visible);
            node->setOpacity(quint8(opacity));
            node->setUserLocked(el.attribute("locked", "0") == "1");
            const int colorLabel = el.attribute("colorlabel", el.attribute("color_label", "0")).toInt();
            node->setColorLabelIndex(colorLabel);
            node->setX(el.attribute("x", "0").toInt());
            node->setY(el.attribute("y", "0").toInt());

            if (KisLayer *l = dynamic_cast<KisLayer *>(node.data())) {
                const bool inheritAlpha = (el.attribute("inherit-alpha", "0") == "1") ||
                                          (el.attribute("inherit_alpha", "0") == "1");
                l->disableAlphaChannel(inheritAlpha);
                const QString op = el.attribute("compositeop").trimmed();
                if (!op.isEmpty()) {
                    l->setCompositeOpId(op);
                }
                if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(l)) {
                    const bool lockAlpha = (el.attribute("lockalpha", "0") == "1") ||
                                           (el.attribute("alpha_locked", "0") == "1");
                    pl->setAlphaLocked(lockAlpha);
                }
            }

            if (bgVisible && (el.attribute("background") == "1" || name == "背景" || name == "Background")) {
                *bgVisible = visible;
            }

            image->addNode(node, parentNode);
            any = true;

            if (isGroup) {
                QDomElement subLayers = el.firstChildElement("layers");
                if (subLayers.isNull()) subLayers = el.firstChildElement("LAYERS");
                if (!subLayers.isNull()) {
                    loadKraNodesDom(subLayers, image, node, store, docName, layerIndexCounter, bgVisible);
                }
            }
        }
    }
    return any;
}

bool ReverieCore::loadKraTree(const QByteArray &maindocBytes, KisImageSP image, KoStore *store, const QString &docName, bool *bgVisible)
{
    if (maindocBytes.isEmpty() || !image || !store) return false;
    QDomDocument doc;
    if (!doc.setContent(maindocBytes)) return false;
    QDomElement rootElem = doc.documentElement();
    QDomElement imgElem = rootElem.firstChildElement("IMAGE");
    if (imgElem.isNull()) imgElem = rootElem.firstChildElement("image");
    if (imgElem.isNull()) imgElem = rootElem;

    QDomElement layersElem = imgElem.firstChildElement("layers");
    if (layersElem.isNull()) layersElem = imgElem.firstChildElement("LAYERS");
    if (layersElem.isNull()) return false;

    int counter = 0;
    return loadKraNodesDom(layersElem, image, image->rootLayer(), store, docName, counter, bgVisible);
}

bool ReverieCore::loadRevp(const QString &path)
{
    qWarning() << "ReverieCore::loadRevp START:" << path;
    QScopedPointer<KoStore> store(KoStore::createStore(path, KoStore::Read, "", KoStore::Zip));
    if (!store) {
        qWarning() << "ReverieCore::loadRevp createStore returned null";
        return false;
    }
    if (store->bad()) {
        qWarning() << "ReverieCore::loadRevp store->bad() is true";
        return false;
    }

    QByteArray metaData;
    if (store->open("meta.json")) {
        metaData = readAllStoreBytes(store.data());
        store->close();
        qWarning() << "ReverieCore::loadRevp read meta.json size:" << metaData.size();
    } else {
        qWarning() << "ReverieCore::loadRevp failed to open meta.json";
    }
    bool isKraFallback = false;
    QImage kraMergedImg;
    int kraW = 0;
    int kraH = 0;
    QByteArray kraMaindocBytes;
    QString kraDocName = QStringLiteral("Artwork");
    if (metaData.isEmpty()) {
        if (store->open("maindoc.xml")) {
            kraMaindocBytes = readAllStoreBytes(store.data());
            store->close();
            if (!kraMaindocBytes.isEmpty()) {
                QDomDocument doc;
                if (doc.setContent(kraMaindocBytes)) {
                    QDomElement rootElem = doc.documentElement();
                    QDomElement imgElem = rootElem.firstChildElement("IMAGE");
                    if (imgElem.isNull()) imgElem = rootElem.firstChildElement("image");
                    if (!imgElem.isNull()) {
                        kraW = imgElem.attribute("width").toInt();
                        kraH = imgElem.attribute("height").toInt();
                        if (imgElem.hasAttribute("name") && !imgElem.attribute("name").isEmpty()) {
                            kraDocName = imgElem.attribute("name");
                        }
                    }
                }
            }
        }
        if (kraW <= 0 || kraH <= 0) {
            bool hasMerged = store->open("mergedimage.png");
            if (!hasMerged) {
                hasMerged = store->open("preview.png");
            }
            if (hasMerged) {
                QByteArray imgData = readAllStoreBytes(store.data());
                store->close();
                if (!imgData.isEmpty() && kraMergedImg.loadFromData(imgData, "PNG")) {
                    kraW = kraMergedImg.width();
                    kraH = kraMergedImg.height();
                }
            }
        } else {
            if (store->open("mergedimage.png")) {
                QByteArray imgData = readAllStoreBytes(store.data());
                store->close();
                if (!imgData.isEmpty()) {
                    kraMergedImg.loadFromData(imgData, "PNG");
                }
            }
        }
        if (kraW > 0 && kraH > 0) {
            isKraFallback = true;
        } else {
            qWarning() << "ReverieCore::loadRevp metaData is empty and no fallback image found";
            return false;
        }
    }

    int w = 1080;
    int h = 1920;
    QJsonObject meta;
    if (isKraFallback) {
        w = kraW;
        h = kraH;
    } else {
        QJsonDocument metaDoc = QJsonDocument::fromJson(metaData);
        if (!metaDoc.isObject()) {
            qWarning() << "ReverieCore::loadRevp metaDoc is not object";
            return false;
        }
        meta = metaDoc.object();
        w = meta["width"].toInt(m_docWidth > 0 ? m_docWidth : 1080);
        h = meta["height"].toInt(m_docHeight > 0 ? m_docHeight : 1920);
    }
    qWarning() << "ReverieCore::loadRevp w:" << w << "h:" << h;

    if (w <= 0 || h <= 0) {
        return false;
    }

    // Reset pipeline & stroke batch state
    m_document.clear();
    m_undoStore = nullptr;
    m_selection = KisSelectionSP();
    m_renderBufW = -1;
    m_renderBufH = -1;
    m_dirtyRect = QRect();
    m_bitmapInited = false;
    m_lastDirty = QRect();
    endStrokeBatch();
    m_strokeDevice = nullptr;
    m_strokeSamples.clear();
    m_strokeHadMove = false;
    m_strokeBatchOpen = false;
    m_drawing = false;
    m_snapshotPending = false;
    delete m_strokeTxn;
    m_strokeTxn = nullptr;
    m_strokeTxnActive = false;
    m_undoStore = new KisSurrogateUndoStore();
    m_redoCount = 0;

    const KoColorSpace *cs = KoColorSpaceRegistry::instance()->rgb8();
    if (!cs) {
        return false;
    }

    KisImageSP image = new KisImage(m_undoStore, w, h, cs, QStringLiteral("Untitled"));
    image->setUndoStore(m_undoStore);
    image->setResolution(72.0, 72.0);

    QJsonArray layersArray = meta["layers"].toArray();
    bool bgLayerVisible = false;

    // 新格式优先: layers.xml 节点树 (含调整层/蒙版等非破坏结构); 无则回退平铺路径
    QByteArray layersXml;
    if (store->open(QStringLiteral("layers.xml"))) {
        layersXml = readAllStoreBytes(store.data());
        store->close();
    }
    bool treeLoaded = false;
    bool treeBgVisible = false;
    if (!layersXml.isEmpty()) {
        treeLoaded = loadLayersXmlTree(layersXml, image, store.data(), &treeBgVisible);
        if (treeLoaded) {
            bgLayerVisible = treeBgVisible;
        }
    }

    if (!treeLoaded && !kraMaindocBytes.isEmpty()) {
        treeLoaded = loadKraTree(kraMaindocBytes, image, store.data(), kraDocName, &treeBgVisible);
        if (treeLoaded) {
            bgLayerVisible = treeBgVisible;
        }
    }

    if (isKraFallback && !treeLoaded) {
        KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
        KoColor white(QColor(Qt::white), cs);
        bg->original()->fill(QRect(0, 0, w, h), white);
        bg->original()->setDirty();
        bg->setUserLocked(true);
        bg->setAlphaLocked(true);
        image->addNode(bg, image->rootLayer());
        bgLayerVisible = true;

        KisPaintLayerSP paint = new KisPaintLayer(image, QStringLiteral("画作"), 255, cs);
        if (!kraMergedImg.isNull()) {
            paint->original()->convertFromQImage(kraMergedImg, 0);
            paint->original()->setDirty();
        }
        image->addNode(paint, image->rootLayer());
    } else if (!treeLoaded) {
    if (layersArray.isEmpty()) {
        KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
        KoColor white(QColor(Qt::white), cs);
        bg->original()->fill(QRect(0, 0, w, h), white);
        bg->original()->setDirty();
        bg->setUserLocked(true);
        bg->setAlphaLocked(true);
        image->addNode(bg, image->rootLayer());
        bgLayerVisible = true;

        KisPaintLayerSP paint = new KisPaintLayer(image, QStringLiteral("颜料图层 1"), 255, cs);
        paint->original()->fill(QRect(0, 0, w, h), KoColor(Qt::transparent, cs));
        paint->original()->setDirty();
        image->addNode(paint, image->rootLayer());
    } else {
        for (int i = 0; i < layersArray.size(); ++i) {
            QJsonObject layerObj = layersArray[i].toObject();
            const QString name = layerObj["name"].toString(i == 0 ? QStringLiteral("背景") : QString("图层 %1").arg(i));
            const bool isBg = (i == 0 || layerObj["background"].toBool(false));
            if (isBg) {
                bgLayerVisible = layerObj["visible"].toBool(true);
            }

            KisPaintLayerSP layer = new KisPaintLayer(image, name, 255, cs);
            if (!layer) continue;

            const double opacity = layerObj["opacity"].toDouble(1.0);
            layer->setOpacity(qBound(0, int(opacity * 255.0 + 0.5), 255));
            layer->setVisible(layerObj["visible"].toBool(true));
            const QString blend = layerObj["blendMode"].toString("normal");
            layer->setCompositeOpId(blend);
            layer->setUserLocked(layerObj["locked"].toBool(isBg));
            layer->setAlphaLocked(layerObj["alphaLocked"].toBool(isBg));
            layer->disableAlphaChannel(layerObj["clipped"].toBool(false));

            const QString layerFileName = QString("layer_%1.png").arg(i, 3, 10, QChar('0'));
            bool loadedPixelData = false;
            if (store->open(layerFileName)) {
                QByteArray lData = readAllStoreBytes(store.data());
                store->close();
                QImage lImg;
                if (!lData.isEmpty() && lImg.loadFromData(lData, "PNG")) {
                    KisPaintDeviceSP dev = layer->paintDevice();
                    if (dev) {
                        dev->clear();
                        dev->convertFromQImage(lImg, 0);
                        dev->setDirty();
                        loadedPixelData = true;
                    }
                }
            }
            if (!loadedPixelData && isBg) {
                KoColor white(QColor(Qt::white), cs);
                layer->original()->fill(QRect(0, 0, w, h), white);
                layer->original()->setDirty();
            }

            image->addNode(layer, image->rootLayer());
        }
    }
    } // !treeLoaded

    if (bgLayerVisible) {
        image->setDefaultProjectionColor(KoColor(Qt::white, cs));
    }

    m_document = image.data();
    m_docWidth = w;
    m_docHeight = h;
    syncLayersFromImage();

    // ---- 动画恢复: 帧率/播放范围/关键帧通道 (仅 revp 新格式) ----
    // 必须在图层已挂到 image 之后创建通道 (keyframeChannelHasBeenAdded
    // 依赖 graphListener), 此处 addNode 均已完成
    if (!isKraFallback && meta.contains("animation")) {
        QJsonObject animObj = meta["animation"].toObject();
        const int fps = animObj["framerate"].toInt(0);
        const int pbStart = animObj["playbackStart"].toInt(0);
        const int pbEnd = animObj["playbackEnd"].toInt(0);
        const int curTime = animObj["currentTime"].toInt(0);

        const QJsonArray layersMeta = meta["layers"].toArray();
        for (int i = 0; i < layersMeta.size(); ++i) {
            QJsonObject layerObj = layersMeta[i].toObject();
            if (!layerObj["animated"].toBool(false)) continue;
            const QJsonArray timesArr = layerObj["keyframes"].toArray();
            if (timesArr.isEmpty()) continue;

            // layers.xml 树加载时索引可能与 meta 错位, 优先按图层名匹配
            const QString name = layerObj["name"].toString();
            int layerIdx = -1;
            for (int j = 0; j < m_layers.size(); ++j) {
                if (m_layers[j].name == name) {
                    layerIdx = j;
                    break;
                }
            }
            if (layerIdx < 0) layerIdx = layerObj["index"].toInt(i);
            KisNode *node = (layerIdx >= 0 && layerIdx < m_layers.size())
                                ? m_layers[layerIdx].node : nullptr;
            if (!node) continue;
            KisRasterKeyframeChannel *channel = revpRasterChannel(node, true);
            if (!channel) continue;

            const int metaIdx = layerObj["index"].toInt(i);
            for (int k = 0; k < timesArr.size(); ++k) {
                const int t = timesArr[k].toInt();
                if (t < 0) continue;
                if (t > 0 && !channel->keyframeAt(t)) {
                    // 加载期不需要撤销记录 (undo store 尚为空)
                    channel->addKeyframe(t, nullptr);
                }
                const QString fn = QString("frame_%1_%2.png")
                                       .arg(metaIdx, 3, 10, QChar('0'))
                                       .arg(t, 5, 10, QChar('0'));
                if (!store->open(fn)) continue;
                QByteArray fData = readAllStoreBytes(store.data());
                store->close();
                QImage fImg;
                if (fData.isEmpty() || !fImg.loadFromData(fData, "PNG")) continue;

                KisRasterKeyframeSP key = channel->keyframeAt<KisRasterKeyframe>(t);
                if (!key) continue;
                KisPaintDeviceSP tmp = new KisPaintDevice(cs);
                tmp->convertFromQImage(fImg, nullptr);
                channel->paintDevice()->framesInterface()->uploadFrame(key->frameID(), tmp);
                channel->paintDevice()->setDirty();
            }

            // 洋葱皮开关还原 (per-paint-layer; 全局配置走 KisImageConfig)
            if (layerObj["onionskin"].toBool(false)) {
                if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(node)) {
                    if (!pl->onionSkinEnabled()) {
                        pl->setOnionSkinEnabled(true);
                        pl->setDirty(KisOnionSkinCompositor::instance()->calculateExtent(pl->paintDevice()));
                    }
                }
            }
        }

        // 还原关键帧色标与末帧保持时长
        m_keyframeTags.clear();
        const QJsonArray tagsArr = animObj["keyframeTags"].toArray();
        for (int k = 0; k < tagsArr.size(); ++k) {
            QJsonObject tagObj = tagsArr[k].toObject();
            loadKeyframeTag(tagObj["layer"].toInt(), tagObj["time"].toInt(), tagObj["tag"].toInt());
        }

        m_lastFrameHold.clear();
        const QJsonObject holdsObj = animObj["lastFrameHolds"].toObject();
        for (auto it = holdsObj.constBegin(); it != holdsObj.constEnd(); ++it) {
            loadLastFrameHold(it.key().toInt(), it.value().toInt(1));
        }

        if (fps > 0) setAnimationFramerate(fps);
        if (pbEnd > pbStart) setAnimationPlaybackRange(pbStart, pbEnd);
        if (curTime > 0) setAnimationCurrentTime(curTime, false);
        bumpKeyframeThumbGen();
    }

    // ---- 导入资源还原: assets/<name> -> m_revAssets (随下次保存写回) ----
    if (!isKraFallback) {
        m_revAssets.clear();
        const QJsonArray assetArr = meta["assets"].toArray();
        for (int i = 0; i < assetArr.size(); ++i) {
            const QString assetName = assetArr[i].toString();
            if (assetName.isEmpty()) continue;
            if (!store->open("assets/" + assetName)) continue;
            const QByteArray aData = readAllStoreBytes(store.data());
            store->close();
            if (!aData.isEmpty()) {
                m_revAssets[assetName] = aData;
            }
        }
    }

    // ---- 存储选区还原: selections/selection_*.png -> m_storedSelections ----
    m_storedSelections.clear();
    if (!isKraFallback && meta.contains("storedSelections")) {
        const QJsonArray selArr = meta["storedSelections"].toArray();
        for (int i = 0; i < selArr.size(); ++i) {
            const QJsonObject sObj = selArr[i].toObject();
            const QString sId = sObj["id"].toString();
            const QString sName = sObj["name"].toString();
            const QString sFile = sObj["file"].toString();
            if (sFile.isEmpty() || !store->open(sFile)) continue;
            const QByteArray pData = readAllStoreBytes(store.data());
            store->close();
            if (pData.isEmpty()) continue;
            QImage img;
            if (img.loadFromData(pData, "PNG")) {
                img = img.convertToFormat(QImage::Format_Grayscale8);
                QVector<quint8> mask(size_t(w) * h, 0);
                const int copyW = qMin(w, img.width());
                const int copyH = qMin(h, img.height());
                for (int y = 0; y < copyH; ++y) {
                    memcpy(mask.data() + size_t(y) * w, img.constScanLine(y), copyW);
                }
                KisSelectionSP sel = selectionFromMask(image, mask);
                StoredSelection item;
                item.id = sId.isEmpty() ? QUuid::createUuid().toString(QUuid::WithoutBraces) : sId;
                item.name = sName.isEmpty() ? QStringLiteral("选区 %1").arg(i + 1) : sName;
                item.selection = sel;
                m_storedSelections.append(item);
            }
        }
    }

    recompositeProjection();
    m_redoCount = 0;
    m_currentLayer = qBound(0, 1, m_layers.size() - 1);
    markDirty();
    return true;
}

bool ReverieCore::loadPsd(const QString &path)
{
    qWarning() << "ReverieCore::loadPsd START:" << path;
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        qWarning() << "ReverieCore::loadPsd failed to open file:" << path;
        return false;
    }

    PSDHeader header;
    if (!header.read(file)) {
        qWarning() << "ReverieCore::loadPsd failed reading header:" << header.error;
        return false;
    }

    const int w = header.width;
    const int h = header.height;
    if (w <= 0 || h <= 0) {
        qWarning() << "ReverieCore::loadPsd invalid dimensions w:" << w << "h:" << h;
        return false;
    }

    PSDColorModeBlock colorModeBlock(header.colormode);
    if (!colorModeBlock.read(file)) {
        qWarning() << "ReverieCore::loadPsd failed reading colormode block:" << colorModeBlock.error;
        return false;
    }

    PSDImageResourceSection resourceSection;
    if (!resourceSection.read(file)) {
        qWarning() << "ReverieCore::loadPsd failed reading resource section:" << resourceSection.error;
        qDeleteAll(resourceSection.resources);
        resourceSection.resources.clear();
        return false;
    }

    // Reset pipeline & stroke batch state
    m_document.clear();
    m_undoStore = nullptr;
    m_selection = KisSelectionSP();
    m_renderBufW = -1;
    m_renderBufH = -1;
    m_dirtyRect = QRect();
    m_bitmapInited = false;
    m_lastDirty = QRect();
    endStrokeBatch();
    m_strokeDevice = nullptr;
    m_strokeSamples.clear();
    m_strokeHadMove = false;
    m_strokeBatchOpen = false;
    m_drawing = false;
    m_snapshotPending = false;
    delete m_strokeTxn;
    m_strokeTxn = nullptr;
    m_strokeTxnActive = false;
    m_undoStore = new KisSurrogateUndoStore();
    m_redoCount = 0;

    const KoColorSpace *cs = KoColorSpaceRegistry::instance()->rgb8();
    if (!cs) {
        qDeleteAll(resourceSection.resources);
        resourceSection.resources.clear();
        return false;
    }

    KisImageSP image = new KisImage(m_undoStore, w, h, cs, QStringLiteral("Untitled"));
    image->setUndoStore(m_undoStore);

    // Read resolution from resourceSection if present
    double xRes = 1.0;
    double yRes = 1.0;
    if (resourceSection.resources.contains(PSDImageResourceSection::RESN_INFO)) {
        RESN_INFO_1005 *resInfo = dynamic_cast<RESN_INFO_1005*>(resourceSection.resources[PSDImageResourceSection::RESN_INFO]->resource);
        if (resInfo && resInfo->hRes > 0 && resInfo->vRes > 0) {
            xRes = static_cast<qreal>(resInfo->hRes) / 72.0;
            yRes = static_cast<qreal>(resInfo->vRes) / 72.0;
        }
    }
    image->setResolution(xRes, yRes);

    qDeleteAll(resourceSection.resources);
    resourceSection.resources.clear();

    // ReveriePaint standard white background layer at index 0
    KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
    KoColor white(QColor(Qt::white), cs);
    bg->original()->fill(QRect(0, 0, w, h), white);
    bg->original()->setDirty();
    bg->setUserLocked(true);
    bg->setAlphaLocked(true);
    image->addNode(bg, image->rootLayer());

    PSDLayerMaskSection layerSection(header);
    const bool hasLayerSection = layerSection.read(file);

    int loadedLayersCount = 0;
    QStack<KisGroupLayerSP> groupStack;
    groupStack.push(image->rootLayer());
    KisNodeSP lastAddedLayer;

    if (hasLayerSection && !layerSection.layers.isEmpty()) {
        for (int i = 0; i < layerSection.layers.size(); ++i) {
            PSDLayerRecord *rec = layerSection.layers[i];
            if (!rec) continue;

            // Handle folder/section dividers (lsct block)
            if (rec->infoBlocks.keys.contains("lsct") &&
                rec->infoBlocks.sectionDividerType != psd_other) {

                if (rec->infoBlocks.sectionDividerType == psd_bounding_divider && !groupStack.isEmpty()) {
                    KisGroupLayerSP groupLayer = new KisGroupLayer(image, QStringLiteral("temp"), 255, cs);
                    image->addNode(groupLayer, groupStack.top());
                    groupStack.push(groupLayer);
                    lastAddedLayer = groupLayer;
                }
                else if ((rec->infoBlocks.sectionDividerType == psd_open_folder ||
                          rec->infoBlocks.sectionDividerType == psd_closed_folder) &&
                         (groupStack.size() > 1 || (lastAddedLayer && !groupStack.isEmpty()))) {
                    KisGroupLayerSP groupLayer;
                    if (groupStack.size() <= 1) {
                        groupLayer = new KisGroupLayer(image, QStringLiteral("temp"), 255, cs);
                        image->addNode(groupLayer, groupStack.top());
                        image->moveNode(lastAddedLayer, groupLayer, KisNodeSP());
                    } else {
                        groupLayer = groupStack.pop();
                    }

                    QString name = rec->layerName.trimmed();
                    if (name.isEmpty() || name == QStringLiteral("UNINITIALIZED")) {
                        name = QStringLiteral("图层组");
                    }
                    groupLayer->setName(name);
                    groupLayer->setVisible(rec->visible);
                    groupLayer->setOpacity(rec->opacity);
                    groupLayer->setColorLabelIndex(rec->labelColor);

                    QString compositeOp = psd_blendmode_to_composite_op(rec->infoBlocks.sectionDividerBlendMode);
                    if (compositeOp == COMPOSITE_PASS_THROUGH) {
                        compositeOp = COMPOSITE_OVER;
                        groupLayer->setPassThroughMode(true);
                    }
                    if (!compositeOp.isEmpty()) {
                        groupLayer->setCompositeOpId(compositeOp);
                    }
                    lastAddedLayer = groupLayer;
                    loadedLayersCount++;
                }
                continue;
            }

            QString name = rec->layerName.trimmed();
            if (name.isEmpty() || name == QStringLiteral("UNINITIALIZED")) {
                name = QString("图层 %1").arg(loadedLayersCount + 1);
            }

            KisPaintLayerSP layer = new KisPaintLayer(image, name, rec->opacity, cs);
            if (!layer) continue;

            if (rec->readPixelData(file, layer->paintDevice())) {
                QString op = psd_blendmode_to_composite_op(rec->blendModeKey);
                if (!op.isEmpty()) {
                    layer->setCompositeOpId(op);
                }
                layer->setVisible(rec->visible);
                layer->disableAlphaChannel(rec->clipping > 0);
                layer->setAlphaLocked(rec->transparencyProtected);
                layer->setColorLabelIndex(rec->labelColor);
                image->addNode(layer, groupStack.isEmpty() ? image->rootLayer() : groupStack.top());
                lastAddedLayer = layer;
                loadedLayersCount++;
            }
        }
    }

    // Fallback if no individual layers could be read: read flattened composite
    if (loadedLayersCount == 0) {
        KisPaintLayerSP flatLayer = new KisPaintLayer(image, QStringLiteral("画作"), 255, cs);
        PSDImageData imageData(&header);
        if (imageData.read(file, flatLayer->paintDevice())) {
            image->addNode(flatLayer, image->rootLayer());
            loadedLayersCount++;
        }
    }

    if (loadedLayersCount == 0) {
        qWarning() << "ReverieCore::loadPsd failed: no pixel data read";
        return false;
    }

    image->setDefaultProjectionColor(KoColor(Qt::white, cs));
    m_document = image.data();
    m_docWidth = w;
    m_docHeight = h;
    syncLayersFromImage();
    recompositeProjection();
    m_redoCount = 0;
    m_currentLayer = qBound(0, 1, m_layers.size() - 1);
    markDirty();
    qWarning() << "ReverieCore::loadPsd SUCCESS: layers=" << m_layers.size() << "w=" << w << "h=" << h;
    return true;
}

static void writeKraNodesXml(QXmlStreamWriter &xml,
                             KisNodeSP parentNode,
                             const QString &docName,
                             KoStore *store,
                             int &layerCounter,
                             KisImageSP image)
{
    if (!parentNode) return;

    for (KisNodeSP node = parentNode->lastChild(); node; node = node->prevSibling()) {
        KisLayer *layer = dynamic_cast<KisLayer *>(node.data());
        if (!layer) continue;

        const bool isGroup = dynamic_cast<KisGroupLayer *>(layer) != nullptr;
        const QString name = layer->name();
        const int opacityVal = layer->opacity();
        QString blend = layer->compositeOpId().trimmed();
        if (blend.isEmpty()) blend = QStringLiteral("normal");

        const QString visibleStr = layer->visible() ? QStringLiteral("1") : QStringLiteral("0");
        const QString lockedStr = layer->userLocked() ? QStringLiteral("1") : QStringLiteral("0");
        const QString inheritAlphaStr = layer->alphaChannelDisabled() ? QStringLiteral("1") : QStringLiteral("0");

        if (isGroup) {
            xml.writeStartElement(QStringLiteral("layer"));
            xml.writeAttribute(QStringLiteral("name"), name);
            xml.writeAttribute(QStringLiteral("opacity"), QString::number(opacityVal));
            xml.writeAttribute(QStringLiteral("compositeop"), blend);
            xml.writeAttribute(QStringLiteral("visible"), visibleStr);
            xml.writeAttribute(QStringLiteral("locked"), lockedStr);
            xml.writeAttribute(QStringLiteral("inherit-alpha"), inheritAlphaStr);
            xml.writeAttribute(QStringLiteral("colormodelname"), QStringLiteral("RGBA"));
            xml.writeAttribute(QStringLiteral("channelformat"), QStringLiteral("U8"));
            xml.writeAttribute(QStringLiteral("nodetype"), QStringLiteral("grouplayer"));
            xml.writeAttribute(QStringLiteral("x"), QString::number(layer->x()));
            xml.writeAttribute(QStringLiteral("y"), QString::number(layer->y()));

            xml.writeStartElement(QStringLiteral("layers"));
            writeKraNodesXml(xml, node, docName, store, layerCounter, image);
            xml.writeEndElement(); // layers

            xml.writeEndElement(); // layer
        } else if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(layer)) {
            const QString layerFileName = QString("layer%1").arg(layerCounter++);
            const QString alphaLockedStr = pl->alphaLocked() ? QStringLiteral("1") : QStringLiteral("0");

            xml.writeStartElement(QStringLiteral("layer"));
            xml.writeAttribute(QStringLiteral("name"), name);
            xml.writeAttribute(QStringLiteral("opacity"), QString::number(opacityVal));
            xml.writeAttribute(QStringLiteral("compositeop"), blend);
            xml.writeAttribute(QStringLiteral("visible"), visibleStr);
            xml.writeAttribute(QStringLiteral("locked"), lockedStr);
            xml.writeAttribute(QStringLiteral("lockalpha"), alphaLockedStr);
            xml.writeAttribute(QStringLiteral("inherit-alpha"), inheritAlphaStr);
            xml.writeAttribute(QStringLiteral("filename"), layerFileName);
            xml.writeAttribute(QStringLiteral("colormodelname"), QStringLiteral("RGBA"));
            xml.writeAttribute(QStringLiteral("channelformat"), QStringLiteral("U8"));
            xml.writeAttribute(QStringLiteral("nodetype"), QStringLiteral("paintlayer"));
            xml.writeAttribute(QStringLiteral("x"), QString::number(layer->x()));
            xml.writeAttribute(QStringLiteral("y"), QString::number(layer->y()));
            xml.writeEndElement(); // layer

            // Write Krita tile data to <docName>/layers/<layerFileName>
            const QString tileLoc = QString("%1/layers/%2").arg(docName, layerFileName);
            if (store->open(tileLoc)) {
                KisStorePaintDeviceWriter writer(store);
                pl->paintDevice()->write(writer);
                store->close();
            }
            if (store->open(tileLoc + ".defaultpixel")) {
                const int pxSize = pl->paintDevice()->colorSpace()->pixelSize();
                store->write(reinterpret_cast<const char*>(pl->paintDevice()->defaultPixel().data()), pxSize);
                store->close();
            }
        } else {
            xml.writeStartElement(QStringLiteral("layer"));
            xml.writeAttribute(QStringLiteral("name"), name);
            xml.writeAttribute(QStringLiteral("opacity"), QString::number(opacityVal));
            xml.writeAttribute(QStringLiteral("compositeop"), blend);
            xml.writeAttribute(QStringLiteral("visible"), visibleStr);
            xml.writeAttribute(QStringLiteral("locked"), lockedStr);
            xml.writeAttribute(QStringLiteral("inherit-alpha"), inheritAlphaStr);
            xml.writeAttribute(QStringLiteral("colormodelname"), QStringLiteral("RGBA"));
            xml.writeAttribute(QStringLiteral("channelformat"), QStringLiteral("U8"));
            xml.writeAttribute(QStringLiteral("nodetype"), QStringLiteral("paintlayer"));
            xml.writeAttribute(QStringLiteral("x"), QString::number(layer->x()));
            xml.writeAttribute(QStringLiteral("y"), QString::number(layer->y()));
            xml.writeEndElement();
        }
    }
}

bool ReverieCore::saveKra(const QString &path)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }

    QScopedPointer<KoStore> store(KoStore::createStore(path, KoStore::Write, "application/x-krita", KoStore::Zip));
    if (!store || store->bad()) {
        return false;
    }

    // 1. mimetype (must be first file, uncompressed)
    if (store->open("mimetype")) {
        store->write(QByteArray("application/x-krita"));
        store->close();
    }

    const QString docName = image->objectName().isEmpty() ? QStringLiteral("Artwork") : image->objectName();

    // 2. maindoc.xml - standard Krita XML specification with layer hierarchies, inherit-alpha & blend modes
    QByteArray maindocBytes;
    {
        QXmlStreamWriter xml(&maindocBytes);
        xml.setAutoFormatting(true);
        xml.writeStartDocument(QStringLiteral("1.0"), true);
        xml.writeDTD(QStringLiteral("<!DOCTYPE DOC PUBLIC '-//KDE//DTD create 1.2//EN' 'http://www.calligra.org/DTD/kra-1.2.dtd'>"));
        xml.writeStartElement(QStringLiteral("DOC"));
        xml.writeAttribute(QStringLiteral("xmlns"), QStringLiteral("http://www.calligra.org/DTD/kra"));
        xml.writeAttribute(QStringLiteral("syntaxVersion"), QStringLiteral("2"));
        xml.writeAttribute(QStringLiteral("editor"), QStringLiteral("Krita"));
        xml.writeAttribute(QStringLiteral("mime"), QStringLiteral("application/x-krita"));

        xml.writeStartElement(QStringLiteral("IMAGE"));
        xml.writeAttribute(QStringLiteral("name"), docName);
        xml.writeAttribute(QStringLiteral("width"), QString::number(image->width()));
        xml.writeAttribute(QStringLiteral("height"), QString::number(image->height()));
        xml.writeAttribute(QStringLiteral("mime"), QStringLiteral("application/x-krita"));
        xml.writeAttribute(QStringLiteral("description"), QString());
        xml.writeAttribute(QStringLiteral("x-res"), QStringLiteral("72"));
        xml.writeAttribute(QStringLiteral("y-res"), QStringLiteral("72"));

        xml.writeStartElement(QStringLiteral("layers"));
        int layerCounter = 0;
        writeKraNodesXml(xml, image->rootLayer(), docName, store.data(), layerCounter, image);
        xml.writeEndElement(); // layers

        xml.writeEndElement(); // IMAGE
        xml.writeEndElement(); // DOC
        xml.writeEndDocument();
    }

    if (store->open("maindoc.xml")) {
        store->write(maindocBytes);
        store->close();
    }

    // 2.1 documentinfo.xml - Calligra / Krita Dublin Core metadata
    if (m_authorProfile.enabled && !m_authorProfile.isEmpty()) {
        const QString docTitle = image->objectName().isEmpty() ? QStringLiteral("Artwork") : image->objectName();
        const QString nowIso = QDateTime::currentDateTime().toString(Qt::ISODate);
        const QString creator = m_authorProfile.nickname.isEmpty() ? m_authorProfile.name : m_authorProfile.nickname;

        auto escapeXml = [](QString s) -> QString {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
        };

        const QString docInfo = QStringLiteral(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<!DOCTYPE document-info PUBLIC \"-//KDE//DTD document-info 1.0//EN\" \"http://www.calligra.org/DTD/document-info-1.0.dtd\">\n"
            "<document-info xmlns=\"http://www.calligra.org/DTD/document-info\">\n"
            " <about>\n"
            "  <title>%1</title>\n"
            "  <description></description>\n"
            "  <subject></subject>\n"
            "  <abstract></abstract>\n"
            "  <keyword></keyword>\n"
            "  <initial-creator>%2</initial-creator>\n"
            "  <editing-cycles>1</editing-cycles>\n"
            "  <editing-time>0</editing-time>\n"
            "  <date>%3</date>\n"
            "  <creation-date>%4</creation-date>\n"
            "  <license>%5</license>\n"
            " </about>\n"
            " <author>\n"
            "  <full-name>%6</full-name>\n"
            "  <creator>%7</creator>\n"
            "  <position></position>\n"
            "  <company>%8</company>\n"
            "  <email>%9</email>\n"
            "  <telephone></telephone>\n"
            "  <contact>%10</contact>\n"
            " </author>\n"
            "</document-info>\n"
        ).arg(escapeXml(docTitle))
         .arg(escapeXml(creator))
         .arg(nowIso)
         .arg(nowIso)
         .arg(escapeXml(m_authorProfile.copyright))
         .arg(escapeXml(m_authorProfile.name))
         .arg(escapeXml(creator))
         .arg(escapeXml(m_authorProfile.organization))
         .arg(escapeXml(m_authorProfile.email))
         .arg(escapeXml(m_authorProfile.website));

        if (store->open("documentinfo.xml")) {
            store->write(docInfo.toUtf8());
            store->close();
        }
    }

    // 3. Merged Preview & mergedimage.png
    const QImage comp = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (!comp.isNull()) {
        if (store->open("preview.png")) {
            QByteArray thumbBytes;
            QBuffer tbuf(&thumbBytes);
            tbuf.open(QIODevice::WriteOnly);
            const QImage thumb = comp.scaled(400, 400, Qt::KeepAspectRatio, Qt::SmoothTransformation);
            thumb.save(&tbuf, "PNG");
            store->write(thumbBytes);
            store->close();
        }
        if (store->open("mergedimage.png")) {
            QByteArray compBytes;
            QBuffer cbuf(&compBytes);
            cbuf.open(QIODevice::WriteOnly);
            comp.save(&cbuf, "PNG");
            store->write(compBytes);
            store->close();
        }
    }

    // 4. Save layer image devices as PNGs
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;

        const QRect bounds = dev->exactBounds();
        QImage layerImg;
        if (!bounds.isEmpty()) {
            layerImg = dev->convertToQImage(nullptr, 0, 0, image->width(), image->height());
        } else {
            layerImg = QImage(image->width(), image->height(), QImage::Format_ARGB32_Premultiplied);
            layerImg.fill(Qt::transparent);
        }

        QByteArray lBytes;
        QBuffer lBuf(&lBytes);
        lBuf.open(QIODevice::WriteOnly);
        layerImg.save(&lBuf, "PNG");

        const QString l1 = QString("layer_%1.png").arg(i, 3, 10, QChar('0'));
        if (store->open(l1)) {
            store->write(lBytes);
            store->close();
        }
        const QString l2 = QString("layer%1.png").arg(i);
        if (store->open(l2)) {
            store->write(lBytes);
            store->close();
        }
    }

    // 5. Also write meta.json for roundtrip
    QJsonObject meta;
    meta["version"] = 1;
    meta["appName"] = "ReveriePaint";
    meta["width"] = image->width();
    meta["height"] = image->height();
    meta["colorMode"] = "RGB";
    meta["colorDepth"] = 8;
    meta["xRes"] = image->xRes();
    meta["yRes"] = image->yRes();
    meta["createdTime"] = QDateTime::currentDateTime().toString(Qt::ISODate);
    meta["modifiedTime"] = QDateTime::currentDateTime().toString(Qt::ISODate);

    QJsonArray layersArray;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        QJsonObject layerObj;
        layerObj["index"] = i;
        layerObj["name"] = e.name;
        layerObj["visible"] = e.visible;
        layerObj["opacity"] = layerOpacity(i);
        layerObj["blendMode"] = layerBlendMode(i);
        layerObj["locked"] = e.locked;
        layerObj["alphaLocked"] = e.alphaLocked;
        layerObj["clipped"] = e.clipped;
        layerObj["isGroup"] = e.isGroup;
        layerObj["depth"] = e.depth;
        layerObj["colorLabel"] = e.colorLabel;
        layerObj["background"] = e.background;
        layersArray.append(layerObj);
    }
    meta["layers"] = layersArray;

    if (m_authorProfile.enabled && !m_authorProfile.isEmpty()) {
        QJsonObject authorObj;
        authorObj["name"] = m_authorProfile.name;
        authorObj["nickname"] = m_authorProfile.nickname;
        authorObj["organization"] = m_authorProfile.organization;
        authorObj["email"] = m_authorProfile.email;
        authorObj["website"] = m_authorProfile.website;
        authorObj["copyright"] = m_authorProfile.copyright;
        meta["author"] = authorObj;
    }

    if (store->open("meta.json")) {
        QJsonDocument doc(meta);
        store->write(doc.toJson(QJsonDocument::Indented));
        store->close();
    }

    // 兼容 KRA 语义的图层树元数据 (加载端重建非破坏节点树用)
    if (store->open("layers.xml")) {
        QString xml;
        writeLayersXml(&xml);
        store->write(xml.toUtf8());
        store->close();
    }

    store.reset();
    QFile f(path);
    return f.exists() && f.size() > 0;
}

bool ReverieCore::loadPng(const QString &path)
{
    QImage img(path);
    if (img.isNull()) {
        return false;
    }
    if (!newDocument(img.width(), img.height())) {
        return false;
    }
    KisImageSP image = m_document;
    if (!image || m_layers.size() < 2) {
        return false;
    }
    const LayerEntry &dest = m_layers[m_layers.size() - 1];
    KisPaintDeviceSP dev = dest.isGroup ? KisPaintDeviceSP()
                                        : layerPaintDeviceFor(dest);
    if (!dev) {
        return false;
    }
    const QImage conv = img.convertToFormat(QImage::Format_ARGB32_Premultiplied);
    const int iw = conv.width();
    const int ih = conv.height();
    QVector<quint8> bytes(size_t(iw) * ih * 4);
    memcpy(bytes.data(), conv.constBits(), size_t(iw) * ih * 4);
    dev->writeBytes(reinterpret_cast<const quint8 *>(bytes.constData()), 0, 0, iw, ih);
    dev->setDirty();
    if (m_undoStore) {
        m_undoStore->clear();
    }
    m_redoCount = 0;
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::renderLayerThumb(int index, int w, int h, void *dstPixels, int dstStride)
{
    if (!m_document || index < 0 || index >= m_layers.size() || !dstPixels || w <= 0 || h <= 0 || dstStride < w * 4) {
        return false;
    }
    if (m_layers[index].nodeType == NodeTypeAdjustment) {
        return false;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return false;
    }
    const QRect ext = dev->extent();

    // Thumbnail cache: while the layer's content generation and extent
    // are unchanged (and the requested size matches), re-blit the
    // tiny cached thumb instead of regenerating it.
    ThumbCache &cache = m_thumbCache[m_layers[index].node];
    if (cache.imgGen == cache.gen && cache.bounds == ext && cache.img.size() == QSize(w, h)) {
        const int copyH = qMin(h, cache.img.height());
        for (int y = 0; y < copyH; ++y) {
            memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
                   cache.img.constScanLine(y), size_t(w) * 4);
        }
        return true;
    }

    QImage out(w, h, QImage::Format_RGBA8888);
    out.fill(Qt::transparent);

    if (!ext.isEmpty()) {
        try {
            const QImage thumb = dev->createThumbnail(w, h, Qt::KeepAspectRatio, KisThumbnailBoundsMode::Coarse);
            if (!thumb.isNull()) {
                QPainter p(&out);
                p.drawImage(QPointF((w - thumb.width()) / 2.0, (h - thumb.height()) / 2.0), thumb);
                p.end();
            }
        } catch (...) {
            // Fallback to transparent thumbnail if creation throws
        }
    }
    cache.img = out;
    cache.imgGen = cache.gen;
    cache.bounds = ext;

    const int copyH = qMin(h, out.height());
    for (int y = 0; y < copyH; ++y) {
        memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
               out.constScanLine(y), size_t(w) * 4);
    }
    return true;
}

int ReverieCore::docWidth() const
{
    return m_docWidth;
}

int ReverieCore::docHeight() const
{
    return m_docHeight;
}
