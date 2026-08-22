/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreIO.cpp - File I/O: PSD save/load, thumbnails, project serialization
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

bool ReverieCore::savePng(const QString &path)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull()) {
        return false;
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

bool ReverieCore::saveRevp(const QString &path, const QString &extraMetaJson, const QByteArray &recordingBlob)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }

    syncLayersFromImage();

    QScopedPointer<KoStore> store(KoStore::createStore(path, KoStore::Write, "application/x-reveriepaint", KoStore::Zip));
    if (!store || store->bad()) {
        return false;
    }

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
        layersArray.append(layerObj);
    }
    meta["layers"] = layersArray;

    // Write meta.json
    if (store->open("meta.json")) {
        QJsonDocument doc(meta);
        QByteArray data = doc.toJson(QJsonDocument::Indented);
        store->write(data);
        store->close();
    }

    // 2. Merged Preview thumbnail
    const QImage comp = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
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

    // 3. Save each layer as PNG tile / image
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(e);
        if (!dev) continue;

        QImage layerImg = dev->convertToQImage(nullptr, 0, 0, image->width(), image->height());
        if (layerImg.isNull()) {
            layerImg = QImage(image->width(), image->height(), QImage::Format_ARGB32_Premultiplied);
            layerImg.fill(Qt::transparent);
        }

        const QString layerFileName = QString("layer_%1.png").arg(i, 3, 10, QChar('0'));
        if (store->open(layerFileName)) {
            QByteArray lBytes;
            QBuffer lBuf(&lBytes);
            lBuf.open(QIODevice::WriteOnly);
            layerImg.save(&lBuf, "PNG");
            store->write(lBytes);
            store->close();
        }
    }

    // Recording entry: the replay event stream, written directly by the
    // store so no post-save ZIP repackage is needed (streamed, no copy)
    if (!recordingBlob.isEmpty()) {
        if (store->open("recording")) {
            store->write(recordingBlob);
            store->close();
        }
    }

    // Finalize the archive by destroying the KoStore instance, which flushes central directory
    store.reset();

    QFile f(path);
    return f.exists() && f.size() > 0;
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
    if (metaData.isEmpty()) {
        qWarning() << "ReverieCore::loadRevp metaData is empty";
        return false;
    }

    QJsonDocument metaDoc = QJsonDocument::fromJson(metaData);
    if (!metaDoc.isObject()) {
        qWarning() << "ReverieCore::loadRevp metaDoc is not object";
        return false;
    }
    QJsonObject meta = metaDoc.object();
    const int w = meta["width"].toInt(m_docWidth > 0 ? m_docWidth : 1080);
    const int h = meta["height"].toInt(m_docHeight > 0 ? m_docHeight : 1920);
    qWarning() << "ReverieCore::loadRevp w:" << w << "h:" << h;

    if (w <= 0 || h <= 0) {
        return false;
    }

    // Reset pipeline & stroke batch state
    m_document.clear();
    m_undoStore = nullptr;
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

    if (bgLayerVisible) {
        image->setDefaultProjectionColor(KoColor(Qt::white, cs));
    }

    m_document = image.data();
    m_docWidth = w;
    m_docHeight = h;
    syncLayersFromImage();
    recompositeProjection();
    m_redoCount = 0;
    m_currentLayer = qBound(0, 1, m_layers.size() - 1);
    markDirty();
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
        if (e.isGroup) continue;
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

    if (store->open("meta.json")) {
        QJsonDocument doc(meta);
        store->write(doc.toJson(QJsonDocument::Indented));
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
    if (!m_document || index < 0 || index >= m_layers.size()) {
        return false;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return false;
    }
    const QRect ext = dev->exactBounds();

    // Thumbnail cache: while the layer's content generation and exact
    // bounds are unchanged (and the requested size matches), re-blit the
    // tiny cached thumb instead of converting the whole layer to QImage
    // and smooth-scaling it on every panel refresh.
    ThumbCache &cache = m_thumbCache[m_layers[index].node];
    if (cache.imgGen == cache.gen && cache.bounds == ext && cache.img.size() == QSize(w, h)) {
        const int copyH = qMin(h, cache.img.height());
        for (int y = 0; y < copyH; ++y) {
            memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
                   cache.img.constScanLine(y), size_t(w) * 4);
        }
        return true;
    }

    QImage out;
    if (ext.isEmpty()) {
        out = QImage(w, h, QImage::Format_RGBA8888);
        out.fill(Qt::transparent);
    } else {
        QImage full = dev->convertToQImage(nullptr, ext.x(), ext.y(), ext.width(), ext.height());
        if (full.isNull()) {
            return false;
        }
        out = QImage(w, h, QImage::Format_RGBA8888);
        out.fill(Qt::transparent);
        const QImage scaled =
            full.scaled(w, h, Qt::KeepAspectRatio, Qt::SmoothTransformation);
        if (!scaled.isNull()) {
            QPainter p(&out);
            p.drawImage(QPointF((w - scaled.width()) / 2.0, (h - scaled.height()) / 2.0), scaled);
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
