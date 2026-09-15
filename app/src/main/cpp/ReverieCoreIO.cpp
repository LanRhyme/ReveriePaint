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
    if (!resourceSection.write(file)) {
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

// 动画关键帧的导出单元: (图层索引, 帧号) -> 整幅画布 PNG
struct RevpKeyframe {
    int layer;
    int time;
    QImage img;
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

bool writeRevpStore(const QString &path,
                    const QJsonObject &meta,
                    const QString &layersXml,
                    const QImage &comp,
                    const QVector<QPair<int, QImage>> &layerImages,
                    const QVector<RevpKeyframe> &keyframeImages,
                    const QMap<QString, QByteArray> &assets,
                    const QByteArray &recordingBlob)
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

    // 2. Merged Preview thumbnail
    if (!comp.isNull()) {
        if (store->open("preview.png")) {
            QByteArray pngBytes;
            QBuffer buf(&pngBytes);
            buf.open(QIODevice::WriteOnly);
            comp.save(&buf, "PNG");
            store->write(pngBytes);
            store->close();
        }
        if (store->open("thumbnail.png")) {
            QByteArray thumbBytes;
            QBuffer tbuf(&thumbBytes);
            tbuf.open(QIODevice::WriteOnly);
            const QImage thumb = comp.scaled(400, 400, Qt::KeepAspectRatio, Qt::SmoothTransformation);
            thumb.save(&tbuf, "PNG");
            store->write(thumbBytes);
            store->close();
        }
    }

    // 3. Layer images
    for (const auto &pair : layerImages) {
        const int idx = pair.first;
        const QImage &layerImg = pair.second;
        const QString layerFileName = QString("layer_%1.png").arg(idx, 3, 10, QChar('0'));
        if (store->open(layerFileName)) {
            QByteArray lBytes;
            QBuffer lBuf(&lBytes);
            lBuf.open(QIODevice::WriteOnly);
            layerImg.save(&lBuf, "PNG");
            store->write(lBytes);
            store->close();
        }
    }

    // 4. Animation keyframe frames (每动画图层每关键帧一整幅画布 PNG)
    for (const auto &kf : keyframeImages) {
        const QString fn = QString("frame_%1_%2.png")
                               .arg(kf.layer, 3, 10, QChar('0'))
                               .arg(kf.time, 5, 10, QChar('0'));
        if (store->open(fn)) {
            QByteArray kBytes;
            QBuffer kBuf(&kBytes);
            kBuf.open(QIODevice::WriteOnly);
            kf.img.save(&kBuf, "PNG");
            store->write(kBytes);
            store->close();
        }
    }

    // 5. Imported assets (音频/视频等二进制资源, 文件名即资源名)
    for (auto it = assets.constBegin(); it != assets.constEnd(); ++it) {
        if (store->open("assets/" + it.key())) {
            store->write(it.value());
            store->close();
        }
    }

    // 6. Recording
    if (!recordingBlob.isEmpty()) {
        if (store->open("recording")) {
            store->write(recordingBlob);
            store->close();
        }
    }

    store.reset(); // flushes and closes zip

    QFile::remove(path);
    if (!QFile::rename(tmpPath, path)) {
        QFile::remove(path);
        QFile::copy(tmpPath, path);
        QFile::remove(tmpPath);
    }

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
            if (pl->onionSkinEnabled()) {
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
        meta["animation"] = animObj;
    }

    QString xml;
    writeLayersXml(&xml);

    const QImage comp = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);

    QVector<QPair<int, QImage>> layerImages;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;

        QImage layerImg = dev->convertToQImage(nullptr, 0, 0, image->width(), image->height());
        if (layerImg.isNull()) {
            layerImg = QImage(image->width(), image->height(), QImage::Format_ARGB32_Premultiplied);
            layerImg.fill(Qt::transparent);
        }
        layerImages.append(qMakePair(i, layerImg));
    }

    // 动画关键帧画面: 每个动画图层的每个关键帧整幅画布导出
    QVector<RevpKeyframe> keyframeImages;
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
            KisPaintDeviceSP tmp = new KisPaintDevice(dev->colorSpace());
            kfCh->writeToDevice(t, tmp);
            QImage img = tmp->convertToQImage(nullptr, 0, 0, image->width(), image->height());
            if (!img.isNull()) {
                RevpKeyframe kf;
                kf.layer = i;
                kf.time = t;
                kf.img = img;
                keyframeImages.append(kf);
            }
        }
    }

    QJsonArray assetNames;
    for (auto it = m_revAssets.constBegin(); it != m_revAssets.constEnd(); ++it) {
        assetNames.append(it.key());
    }
    if (!assetNames.isEmpty()) {
        meta["assets"] = assetNames;
    }

    return writeRevpStore(path, meta, xml, comp, layerImages, keyframeImages, m_revAssets, recordingBlob);
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
            if (pl->onionSkinEnabled()) {
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
        meta["animation"] = animObj;
    }

    QString xml;
    writeLayersXml(&xml);

    // Deep detached copies of images captured in ~8ms on caller thread
    const QImage comp = image->convertToQImage(0, 0, image->width(), image->height(), nullptr).copy();

    QVector<QPair<int, QImage>> layerImages;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup || e.nodeType == NodeTypeAdjustment) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;

        QImage layerImg = dev->convertToQImage(nullptr, 0, 0, image->width(), image->height());
        if (layerImg.isNull()) {
            layerImg = QImage(image->width(), image->height(), QImage::Format_ARGB32_Premultiplied);
            layerImg.fill(Qt::transparent);
        } else {
            layerImg = layerImg.copy();
        }
        layerImages.append(qMakePair(i, layerImg));
    }

    // 关键帧: 调用线程只做瓦片拷贝 (writeToDevice 到独立设备), PNG 转换放写盘线程
    struct RevpKeyframeDevice {
        int layer;
        int time;
        KisPaintDeviceSP dev;
    };
    QVector<RevpKeyframeDevice> kfDevices;
    const int docW = image->width();
    const int docH = image->height();
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
            KisPaintDeviceSP tmp = new KisPaintDevice(dev->colorSpace());
            kfCh->writeToDevice(t, tmp);
            RevpKeyframeDevice kfd;
            kfd.layer = i;
            kfd.time = t;
            kfd.dev = tmp;
            kfDevices.append(kfd);
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

    s_savingRevpAsync.store(true);
    std::thread([path, meta, xml, comp, layerImages, kfDevices, docW, docH, assetsCopy, recordingBlob]() {
        QVector<RevpKeyframe> keyframeImages;
        for (const auto &kfd : kfDevices) {
            RevpKeyframe kf;
            kf.layer = kfd.layer;
            kf.time = kfd.time;
            kf.img = kfd.dev->convertToQImage(nullptr, 0, 0, docW, docH);
            if (!kf.img.isNull()) {
                keyframeImages.append(kf);
            }
        }
        const bool ok = writeRevpStore(path, meta, xml, comp, layerImages, keyframeImages, assetsCopy, recordingBlob);
        s_savingRevpAsync.store(false);
        qDebug() << "saveRevpAsync finished, result=" << ok << "path=" << path;
    }).detach();

    return true;
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
    if (metaData.isEmpty()) {
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
                if (store->open("maindoc.xml")) {
                    QByteArray xmlData = readAllStoreBytes(store.data());
                    store->close();
                    QXmlStreamReader xml(xmlData);
                    while (!xml.atEnd() && !xml.hasError()) {
                        xml.readNext();
                        if (xml.isStartElement() && xml.name() == QLatin1String("IMAGE")) {
                            auto attrs = xml.attributes();
                            if (attrs.hasAttribute("width")) kraW = attrs.value("width").toInt();
                            if (attrs.hasAttribute("height")) kraH = attrs.value("height").toInt();
                            break;
                        }
                    }
                }
                if (kraW > 0 && kraH > 0) {
                    isKraFallback = true;
                }
            }
        }
        if (!isKraFallback) {
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

    if (isKraFallback) {
        KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
        KoColor white(QColor(Qt::white), cs);
        bg->original()->fill(QRect(0, 0, w, h), white);
        bg->original()->setDirty();
        bg->setUserLocked(true);
        bg->setAlphaLocked(true);
        image->addNode(bg, image->rootLayer());
        bgLayerVisible = true;

        KisPaintLayerSP paint = new KisPaintLayer(image, QStringLiteral("画作"), 255, cs);
        paint->original()->convertFromQImage(kraMergedImg, 0);
        paint->original()->setDirty();
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
    if (hasLayerSection && !layerSection.layers.isEmpty()) {
        for (int i = 0; i < layerSection.layers.size(); ++i) {
            PSDLayerRecord *rec = layerSection.layers[i];
            if (!rec) continue;

            // Skip folder/section dividers
            if (rec->infoBlocks.keys.contains("lsct") &&
                rec->infoBlocks.sectionDividerType != psd_other) {
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
                image->addNode(layer, image->rootLayer());
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

    // 2. maindoc.xml - standard Krita XML specification with layer hierarchies, inherit-alpha & blend modes
    QString xml = QStringLiteral("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                 "<!DOCTYPE DOC PUBLIC '-//KDE//DTD create 1.2//EN' 'http://www.calligra.org/DTD/kra-1.2.dtd'>\n"
                                 "<DOC xmlns=\"http://www.calligra.org/DTD/kra\" syntaxVersion=\"2\" editor=\"Krita\" mime=\"application/x-krita\">\n"
                                 " <IMAGE name=\"%1\" width=\"%2\" height=\"%3\" mime=\"application/x-krita\" description=\"\" x-res=\"72\" y-res=\"72\">\n"
                                 "  <layers>\n")
                      .arg(image->objectName().isEmpty() ? QStringLiteral("Artwork") : image->objectName())
                      .arg(image->width())
                      .arg(image->height());

    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        const int opacityVal = qBound(0, int(layerOpacity(i) * 255.0 + 0.5), 255);
        QString blend = layerBlendMode(i).trimmed();
        if (blend.isEmpty()) blend = QStringLiteral("normal");

        // Convert blend modes to standard Krita composite op IDs
        if (blend == QStringLiteral("正片叠底") || blend.compare("multiply", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("multiply");
        } else if (blend == QStringLiteral("正常") || blend.compare("normal", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("normal");
        } else if (blend == QStringLiteral("滤色") || blend.compare("screen", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("screen");
        } else if (blend == QStringLiteral("叠加") || blend.compare("overlay", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("overlay");
        } else if (blend == QStringLiteral("变暗") || blend.compare("darken", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("darken");
        } else if (blend == QStringLiteral("变亮") || blend.compare("lighten", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("lighten");
        } else if (blend == QStringLiteral("颜色减淡") || blend == QStringLiteral("dodge") || blend.compare("color_dodge", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("color_dodge");
        } else if (blend == QStringLiteral("颜色加深") || blend == QStringLiteral("burn") || blend.compare("color_burn", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("color_burn");
        } else if (blend == QStringLiteral("线性减淡") || blend == QStringLiteral("增加") || blend.compare("linear_dodge", Qt::CaseInsensitive) == 0 || blend.compare("add", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("linear_dodge");
        } else if (blend == QStringLiteral("线性加深") || blend.compare("linear_burn", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("linear_burn");
        } else if (blend == QStringLiteral("强光") || blend.compare("hard_light", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("hard_light");
        } else if (blend == QStringLiteral("柔光") || blend.compare("soft_light", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("soft_light");
        } else if (blend == QStringLiteral("差值") || blend.compare("difference", Qt::CaseInsensitive) == 0) {
            blend = QStringLiteral("difference");
        }

        // Inherit alpha (剪贴蒙版 / 继承透明度)
        const QString inheritAlphaStr = e.clipped ? QStringLiteral("1") : QStringLiteral("0");
        const QString visibleStr = e.visible ? QStringLiteral("1") : QStringLiteral("0");
        const QString lockedStr = (e.locked || e.background) ? QStringLiteral("1") : QStringLiteral("0");
        const QString alphaLockedStr = (e.alphaLocked || e.background) ? QStringLiteral("1") : QStringLiteral("0");
        const QString layerFileNameKra = QString("layer%1").arg(i);

        QString safeName = e.name;
        safeName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");

        xml += QStringLiteral("   <layer name=\"%1\" opacity=\"%2\" compositeop=\"%3\" visible=\"%4\" locked=\"%5\" lockalpha=\"%6\" inherit-alpha=\"%7\" filename=\"%8\" colormodelname=\"RGBA\" channelformat=\"U8\" nodetype=\"paintlayer\" x=\"0\" y=\"0\" />\n")
                   .arg(safeName)
                   .arg(opacityVal)
                   .arg(blend)
                   .arg(visibleStr)
                   .arg(lockedStr)
                   .arg(alphaLockedStr)
                   .arg(inheritAlphaStr)
                   .arg(layerFileNameKra);
    }

    xml += QStringLiteral("  </layers>\n"
                          " </IMAGE>\n"
                          "</DOC>\n");

    if (store->open("maindoc.xml")) {
        store->write(xml.toUtf8());
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
    if (!m_document || index < 0 || index >= m_layers.size() || !dstPixels || w <= 0 || h <= 0) {
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
        const QImage thumb = dev->createThumbnail(w, h, Qt::KeepAspectRatio, KisThumbnailBoundsMode::Coarse);
        if (!thumb.isNull()) {
            QPainter p(&out);
            p.drawImage(QPointF((w - thumb.width()) / 2.0, (h - thumb.height()) / 2.0), thumb);
            p.end();
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
