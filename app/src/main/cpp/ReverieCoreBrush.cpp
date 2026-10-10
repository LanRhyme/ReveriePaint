/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreBrush.cpp - Brush engine: paintop registration, pressure response, brush lifecycle
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "PixelAlpha.h"

#include <QRegularExpression>
#include <QBuffer>
#include <QUrl>
#include <algorithm>
#include <QtEndian>
#include <zlib.h>
#include <QCryptographicHash>

#include <kis_abr_brush_collection.h>
#include <kis_abr_brush.h>
#include <KoEmbeddedResource.h>
// calcAutoSpacing(): 自动间距 (useAutoSpacing="1") 的生效值计算, 与
// KisPaintOpUtils::effectiveSpacing 内部用的是同一个公式。
#include <kis_paintop_utils.h>

float ReverieCore::brushPressureFraction(float pressure)
{
    QMutexLocker locker(&m_sizeCurveMutex);
    auto *settings = m_brushPreset ? m_brushPreset->settings().data() : nullptr;
    if (!settings) return 1.0f;

    // 预设（设置对象）变化时重解析 Size 曲线，其余帧走缓存（零分配）
    if (m_sizeCurveOwner != static_cast<const void *>(settings)) {
        m_sizeCurveOwner = static_cast<const void *>(settings);
        m_sizeCurveCache.clear();
        m_sizeUseCurveCache = settings->getBool("SizeUseCurve", false);
        const QString sensorXml = settings->getString("SizeSensor", QString());
        // 仅 pressure 传感器驱动尺寸；tilt/fuzzy 等视为无压感响应
        if (m_sizeUseCurveCache && sensorXml.contains(QLatin1String("id=\"pressure\""))) {
            QRegularExpression re(QStringLiteral("(-?[\\d.]+),(-?[\\d.]+)"));
            auto it = re.globalMatch(sensorXml);
            while (it.hasNext()) {
                const auto m = it.next();
                m_sizeCurveCache.append(QPointF(m.captured(1).toDouble(), m.captured(2).toDouble()));
            }
            std::sort(m_sizeCurveCache.begin(), m_sizeCurveCache.end(),
                      [](const QPointF &a, const QPointF &b) { return a.x() < b.x(); });
        } else {
            m_sizeUseCurveCache = false;
        }
    }

    if (!m_sizeUseCurveCache || m_sizeCurveCache.size() < 2) return 1.0f;

    const QVector<QPointF> &pts = m_sizeCurveCache;
    const double p = qBound(0.0, static_cast<double>(pressure), 1.0);
    if (p <= pts.first().x()) return static_cast<float>(qBound(0.0, pts.first().y(), 1.0));
    if (p >= pts.last().x()) return static_cast<float>(qBound(0.0, pts.last().y(), 1.0));

    // 两个控制点时严格线性插值，完全对齐 Krita 直线行为
    if (pts.size() == 2) {
        const double t = (p - pts[0].x()) / qMax(1e-9, pts[1].x() - pts[0].x());
        return static_cast<float>(qBound(0.0, pts[0].y() + (pts[1].y() - pts[0].y()) * t, 1.0));
    }

    int i = 0;
    while (i + 2 < pts.size() && pts[i + 1].x() < p) ++i;
    const QPointF &p1 = pts[i];
    const QPointF &p2 = pts[i + 1];
    const QPointF &p0 = pts[qMax(0, i - 1)];
    const QPointF &p3 = pts[qMin(pts.size() - 1, i + 2)];

    // Catmull-Rom 三次插值（与 KisCubicCurve 观感一致的平滑曲线）
    const double t = (p - p1.x()) / qMax(1e-9, p2.x() - p1.x());
    const double t2 = t * t;
    const double t3 = t2 * t;
    const double y = 0.5 * ((2.0 * p1.y())
        + (-p0.y() + p2.y()) * t
        + (2.0 * p0.y() - 5.0 * p1.y() + 4.0 * p2.y() - p3.y()) * t2
        + (-p0.y() + 3.0 * p1.y() - 3.0 * p2.y() + p3.y()) * t3);
    return static_cast<float>(qBound(0.0, y, 1.0));
}

void ReverieCore::setBrushColor(const QColor &c)
{
    m_brushColor = c;
    m_strokeColor = c;
    if (m_strokePainter && m_document && m_document->colorSpace()) {
        m_strokePainter->setPaintColor(KoColor(c, m_document->colorSpace()));
    }
}

void ReverieCore::setBrushSecondaryColor(const QColor &c)
{
    m_brushSecondaryColor = c;
    if (m_strokePainter && m_document && m_document->colorSpace()) {
        m_strokePainter->setBackgroundColor(KoColor(c, m_document->colorSpace()));
    }
}

void ReverieCore::registerPaintOps()
{
    static bool done = false;
    if (!done) {
        // Implemented inside the cross-compiled paintop plugin libraries so
        // the KisSimplePaintOpFactory vtable layout matches libkritaimage's
        // view (instantiating the template in this module produced vtable
        // misalignment and crashes).
        krita_register_default_paintops();
        krita_register_colorsmudge_paintop();
        krita_register_roundmarker_paintop();
        krita_register_spray_paintop();
        krita_register_sketch_paintop();
        krita_register_deform_paintop();
        krita_register_filter_paintop();
        krita_register_grid_paintop();
        krita_register_experiment_paintop();
        krita_register_particle_paintop();
        krita_register_curve_paintop();
        krita_register_tangentnormal_paintop();
        krita_register_hairy_paintop();
        krita_register_hatching_paintop();
        registerCoreFilters();

        const KoColorSpace *cs16 = KoColorSpaceRegistry::instance()->colorSpace(
            RGBAColorModelID.id(), Integer16BitsColorDepthID.id());
        if (cs16 && !cs16->hasCompositeOp(COMPOSITE_COPY)) {
            addStandardCompositeOps<KoBgrU16Traits>(const_cast<KoColorSpace *>(cs16));
        }
        done = true;
    }
}

int ReverieCore::loadBrushPresetsFromDir(const QString &dirPath)
{
    registerPaintOps();
    QDir dir(dirPath);
    const QStringList kpps = dir.entryList(QStringList() << QStringLiteral("*.kpp"),
                                           QDir::Files, QDir::Name);
    m_presets.clear();
    for (const QString &f : kpps) {
        QString name = f;
        name.chop(4);  // strip ".kpp"
        m_presets.append(qMakePair(name, dir.filePath(f)));
    }
    // 预设列表重建后索引全部位移, 元数据缓存按 index 键存, 必须整体失效
    {
        QMutexLocker lock(&m_presetInfoMutex);
        m_presetInfoCache.clear();
    }
    return m_presets.size();
}

bool ReverieCore::loadSingleBrushResource(const QString &baseName)
{
    if (baseName.isEmpty() || (m_brushDir.isEmpty() && m_patternDir.isEmpty())) {
        return false;
    }
    const QString cleanName = QUrl::fromPercentEncoding(baseName.toUtf8());
    const QString bareName = QFileInfo(cleanName).fileName();

    const bool isBrush = bareName.endsWith(QLatin1String(".gbr"), Qt::CaseInsensitive) ||
                         bareName.endsWith(QLatin1String(".gih"), Qt::CaseInsensitive) ||
                         bareName.endsWith(QLatin1String(".png"), Qt::CaseInsensitive) ||
                         bareName.endsWith(QLatin1String(".svg"), Qt::CaseInsensitive) ||
                         bareName.endsWith(QLatin1String(".abr"), Qt::CaseInsensitive);

    if (m_loadedBrushes.contains(baseName) || m_loadedBrushes.contains(cleanName) || m_loadedBrushes.contains(bareName)) {
        return true;
    }
    if (!isBrush && (m_loadedResourceNames.contains(baseName) || m_loadedResourceNames.contains(cleanName) || m_loadedResourceNames.contains(bareName))) {
        return true;
    }

    QString fullPath;
    auto testPath = [](const QString &dir, const QString &name) -> QString {
        if (dir.isEmpty() || name.isEmpty()) return QString();
        const QString p = QDir(dir).filePath(name);
        return QFile::exists(p) ? p : QString();
    };

    if (!m_brushDir.isEmpty()) {
        fullPath = testPath(m_brushDir, baseName);
        if (fullPath.isEmpty()) fullPath = testPath(m_brushDir, cleanName);
        if (fullPath.isEmpty()) fullPath = testPath(m_brushDir, bareName);
    }
    if (fullPath.isEmpty() && !m_patternDir.isEmpty()) {
        fullPath = testPath(m_patternDir, baseName);
        if (fullPath.isEmpty()) fullPath = testPath(m_patternDir, cleanName);
        if (fullPath.isEmpty()) fullPath = testPath(m_patternDir, bareName);
    }

    // Case-insensitive fallback lookup in m_brushDir and m_patternDir
    if (fullPath.isEmpty()) {
        auto findCaseInsensitive = [](const QString &dirPath, const QString &target) -> QString {
            if (dirPath.isEmpty() || target.isEmpty()) return QString();
            QDir d(dirPath);
            const QStringList list = d.entryList(QDir::Files | QDir::NoDotAndDotDot);
            for (const QString &entry : list) {
                if (entry.compare(target, Qt::CaseInsensitive) == 0) {
                    return d.filePath(entry);
                }
            }
            return QString();
        };
        if (!m_brushDir.isEmpty()) fullPath = findCaseInsensitive(m_brushDir, bareName);
        if (fullPath.isEmpty() && !m_patternDir.isEmpty()) fullPath = findCaseInsensitive(m_patternDir, bareName);
    }

    if (fullPath.isEmpty() || !QFile::exists(fullPath)) {
        return false;
    }

    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());

    // Support Photoshop .abr brush collections
    if (bareName.endsWith(QLatin1String(".abr"), Qt::CaseInsensitive)) {
        KisAbrBrushCollection coll(fullPath);
        if (coll.load()) {
            const auto abrList = coll.brushes();
            if (!abrList.isEmpty()) {
                for (KisAbrBrushSP b : abrList) {
                    if (lr) {
                        lr->addResource(b.staticCast<KoResource>());
                    }
                    KisBrushSP bSp = b.staticCast<KisBrush>();
                    if (!b->name().isEmpty()) m_loadedBrushes.insert(b->name(), bSp);
                    if (!b->filename().isEmpty()) m_loadedBrushes.insert(b->filename(), bSp);
                }
                KisBrushSP firstBrush = abrList.first().staticCast<KisBrush>();
                m_loadedBrushes.insert(bareName, firstBrush);
                if (baseName != bareName) {
                    m_loadedBrushes.insert(baseName, firstBrush);
                }
                if (!cleanName.isEmpty() && cleanName != bareName && cleanName != baseName) {
                    m_loadedBrushes.insert(cleanName, firstBrush);
                }
                RPC_LOG("RPC loadSingleBrushResource ABR loaded: %s (count=%d)", fullPath.toUtf8().constData(), static_cast<int>(abrList.size()));
                return true;
            }
        }
        return false;
    }

    KoResource *res = nullptr;
    const bool isPattern = (!m_patternDir.isEmpty() && fullPath.startsWith(m_patternDir)) ||
                           bareName.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive);

    if (isPattern) {
        res = new KoPattern(fullPath);
    } else if (bareName.endsWith(QLatin1String(".gbr"), Qt::CaseInsensitive)) {
        res = new KisGbrBrush(bareName);
    } else if (bareName.endsWith(QLatin1String(".gih"), Qt::CaseInsensitive)) {
        res = new KisImagePipeBrush(bareName);
    } else if (bareName.endsWith(QLatin1String(".png"), Qt::CaseInsensitive)) {
        res = new KisPngBrush(bareName);
    } else if (bareName.endsWith(QLatin1String(".svg"), Qt::CaseInsensitive)) {
        res = new KisSvgBrush(bareName);
    } else if (bareName.endsWith(QLatin1String(".jpg"), Qt::CaseInsensitive) ||
               bareName.endsWith(QLatin1String(".jpeg"), Qt::CaseInsensitive)) {
        QImage img(fullPath);
        if (!img.isNull()) {
            QByteArray pngData;
            QBuffer buf(&pngData);
            buf.open(QIODevice::WriteOnly);
            if (img.save(&buf, "PNG")) {
                buf.close();
                buf.open(QIODevice::ReadOnly);
                res = new KisPngBrush(bareName);
                if (res->loadFromDevice(&buf, m_brushResources)) {
                    res->setFilename(bareName);
                    res->setName(bareName);
                    KisBrush *brushRaw = dynamic_cast<KisBrush *>(res);
                    if (brushRaw) {
                        KisBrushSP brushSp(brushRaw);
                        if (lr) {
                            lr->addResource(brushSp.staticCast<KoResource>());
                        }
                        m_loadedBrushes.insert(bareName, brushSp);
                        if (baseName != bareName) {
                            m_loadedBrushes.insert(baseName, brushSp);
                        }
                        if (!cleanName.isEmpty() && cleanName != bareName && cleanName != baseName) {
                            m_loadedBrushes.insert(cleanName, brushSp);
                        }
                        m_loadedResourceNames.insert(bareName);
                        m_loadedResourceNames.insert(baseName);
                        if (!cleanName.isEmpty()) m_loadedResourceNames.insert(cleanName);
                        return true;
                    } else if (lr) {
                        KoResourceSP resSp(res);
                        lr->addResource(resSp);
                        m_loadedResourceNames.insert(bareName);
                        m_loadedResourceNames.insert(baseName);
                        if (!cleanName.isEmpty()) m_loadedResourceNames.insert(cleanName);
                        return true;
                    }
                }
                delete res;
            }
        }
        return false;
    }
    if (!res) return false;

    QFile f(fullPath);
    if (f.open(QIODevice::ReadOnly)) {
        const QByteArray fileBytes = f.readAll();
        f.seek(0);
        if (res->loadFromDevice(&f, m_brushResources)) {
            res->setFilename(bareName);
            res->setName(bareName);
            const QString md5Hex = QString::fromLatin1(QCryptographicHash::hash(fileBytes, QCryptographicHash::Md5).toHex());
            res->setMD5Sum(md5Hex);
            KisBrush *brushRaw = dynamic_cast<KisBrush *>(res);
            if (brushRaw) {
                KisBrushSP brushSp(brushRaw);
                if (lr) {
                    lr->addResource(brushSp.staticCast<KoResource>());
                    if (baseName != bareName) {
                        KisBrushSP altSp(brushSp->clone().dynamicCast<KisBrush>());
                        if (altSp) {
                            altSp->setFilename(baseName);
                            altSp->setName(baseName);
                            altSp->setMD5Sum(md5Hex);
                            lr->addResource(altSp.staticCast<KoResource>());
                        }
                    }
                    if (bareName.toLower() != bareName) {
                        KisBrushSP lowerSp(brushSp->clone().dynamicCast<KisBrush>());
                        if (lowerSp) {
                            lowerSp->setFilename(bareName.toLower());
                            lowerSp->setName(bareName.toLower());
                            lowerSp->setMD5Sum(md5Hex);
                            lr->addResource(lowerSp.staticCast<KoResource>());
                        }
                    }
                    const QString baseWithoutExt = QFileInfo(bareName).completeBaseName();
                    if (!baseWithoutExt.isEmpty() && baseWithoutExt != bareName) {
                        KisBrushSP noExtSp(brushSp->clone().dynamicCast<KisBrush>());
                        if (noExtSp) {
                            noExtSp->setFilename(baseWithoutExt);
                            noExtSp->setName(baseWithoutExt);
                            noExtSp->setMD5Sum(md5Hex);
                            lr->addResource(noExtSp.staticCast<KoResource>());
                        }
                    }
                }
                m_loadedBrushes.insert(bareName, brushSp);
                if (baseName != bareName) {
                    m_loadedBrushes.insert(baseName, brushSp);
                }
                if (!cleanName.isEmpty() && cleanName != bareName && cleanName != baseName) {
                    m_loadedBrushes.insert(cleanName, brushSp);
                }
                const QString baseWithoutExt = QFileInfo(bareName).completeBaseName();
                if (!baseWithoutExt.isEmpty() && baseWithoutExt != bareName) {
                    m_loadedBrushes.insert(baseWithoutExt, brushSp);
                }
                m_loadedResourceNames.insert(bareName);
                m_loadedResourceNames.insert(baseName);
                if (!cleanName.isEmpty()) m_loadedResourceNames.insert(cleanName);
                if (!baseWithoutExt.isEmpty()) m_loadedResourceNames.insert(baseWithoutExt);
                f.close();
                return true;
            } else {
                KoResourceSP resSp(res);
                if (lr) {
                    lr->addResource(resSp);
                    if (baseName != bareName) {
                        KoResourceSP altSp(resSp->clone());
                        if (altSp) {
                            altSp->setFilename(baseName);
                            altSp->setName(baseName);
                            altSp->setMD5Sum(md5Hex);
                            lr->addResource(altSp);
                        }
                    }
                    if (!cleanName.isEmpty() && cleanName != bareName && cleanName != baseName) {
                        KoResourceSP cleanSp(resSp->clone());
                        if (cleanSp) {
                            cleanSp->setFilename(cleanName);
                            cleanSp->setName(cleanName);
                            cleanSp->setMD5Sum(md5Hex);
                            lr->addResource(cleanSp);
                        }
                    }
                    if (bareName.toLower() != bareName) {
                        KoResourceSP lowerSp(resSp->clone());
                        if (lowerSp) {
                            lowerSp->setFilename(bareName.toLower());
                            lowerSp->setName(bareName.toLower());
                            lowerSp->setMD5Sum(md5Hex);
                            lr->addResource(lowerSp);
                        }
                    }
                    const QString baseWithoutExt = QFileInfo(bareName).completeBaseName();
                    if (!baseWithoutExt.isEmpty() && baseWithoutExt != bareName) {
                        KoResourceSP noExtSp(resSp->clone());
                        if (noExtSp) {
                            noExtSp->setFilename(baseWithoutExt);
                            noExtSp->setName(baseWithoutExt);
                            noExtSp->setMD5Sum(md5Hex);
                            lr->addResource(noExtSp);
                        }
                    }
                    if (bareName.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive)) {
                        const QString noPat = bareName.left(bareName.length() - 4);
                        if (!noPat.isEmpty()) {
                            KoResourceSP noPatSp(resSp->clone());
                            if (noPatSp) {
                                noPatSp->setFilename(noPat);
                                noPatSp->setName(noPat);
                                noPatSp->setMD5Sum(md5Hex);
                                lr->addResource(noPatSp);
                            }
                        }
                    } else {
                        const QString withPat = bareName + QLatin1String(".pat");
                        KoResourceSP withPatSp(resSp->clone());
                        if (withPatSp) {
                            withPatSp->setFilename(withPat);
                            withPatSp->setName(withPat);
                            withPatSp->setMD5Sum(md5Hex);
                            lr->addResource(withPatSp);
                        }
                    }
                }
                m_loadedResourceNames.insert(bareName);
                m_loadedResourceNames.insert(baseName);
                if (!cleanName.isEmpty()) m_loadedResourceNames.insert(cleanName);
                const QString baseWithoutExt = QFileInfo(bareName).completeBaseName();
                if (!baseWithoutExt.isEmpty()) m_loadedResourceNames.insert(baseWithoutExt);
                f.close();
                RPC_LOG("RPC loadSingleBrushResource Pattern loaded: %s (name=%s, md5=%s)",
                        fullPath.toUtf8().constData(), res->name().toUtf8().constData(), md5Hex.toUtf8().constData());
                return true;
            }
        }
        delete res;
        f.close();
    } else {
        delete res;
    }
    return false;
}

static QByteArray inflateDataStream(const uchar *src, int srcLen)
{
    if (!src || srcLen <= 0) return QByteArray();
    z_stream strm;
    memset(&strm, 0, sizeof(strm));
    if (inflateInit(&strm) != Z_OK) return QByteArray();
    strm.next_in = const_cast<Bytef*>(src);
    strm.avail_in = srcLen;

    QByteArray out;
    char buffer[65536];
    int ret = Z_OK;
    while (ret == Z_OK) {
        strm.next_out = reinterpret_cast<Bytef*>(buffer);
        strm.avail_out = sizeof(buffer);
        ret = inflate(&strm, Z_NO_FLUSH);
        if (ret != Z_OK && ret != Z_STREAM_END) {
            inflateEnd(&strm);
            return QByteArray();
        }
        int have = sizeof(buffer) - strm.avail_out;
        out.append(buffer, have);
    }
    inflateEnd(&strm);
    return out;
}

void ReverieCore::ensureBrushForPreset(const QString &kppPath)
{
    if (m_brushDir.isEmpty() || kppPath.isEmpty()) return;
    QFile f(kppPath);
    if (!f.open(QIODevice::ReadOnly)) return;
    const QByteArray data = f.readAll();
    f.close();

    // Check PNG signature
    if (data.size() < 8 || memcmp(data.constData(), "\x89PNG\r\n\x1a\n", 8) != 0) return;

    // Scan PNG chunks for zTXt, tEXt, or iTXt chunk with keyword "preset"
    int idx = 8;
    while (idx + 12 <= data.size()) {
        const quint32 length = qFromBigEndian<quint32>(reinterpret_cast<const uchar*>(data.constData() + idx));
        const char *type = data.constData() + idx + 4;
        const bool isZTxt = (memcmp(type, "zTXt", 4) == 0);
        const bool isTExt = (memcmp(type, "tEXt", 4) == 0);
        const bool isITxt = (memcmp(type, "iTXt", 4) == 0);
        if ((isZTxt || isTExt || isITxt) && idx + 8 + int(length) <= data.size()) {
            const char *chunkData = data.constData() + idx + 8;
            int nullPos = 0;
            while (nullPos < int(length) && chunkData[nullPos] != 0) {
                ++nullPos;
            }
            if (nullPos == 6 && memcmp(chunkData, "preset", 6) == 0) {
                QString xmlStr;
                if (isZTxt && nullPos < int(length) - 2) {
                    const uchar *zStream = reinterpret_cast<const uchar*>(chunkData + nullPos + 2);
                    const int zLen = int(length) - (nullPos + 2);
                    const QByteArray decomp = inflateDataStream(zStream, zLen);
                    if (!decomp.isEmpty()) {
                        xmlStr = QString::fromUtf8(decomp);
                    }
                } else if (isTExt) {
                    xmlStr = QString::fromUtf8(chunkData + nullPos + 1, int(length) - (nullPos + 1));
                } else if (isITxt && nullPos + 3 <= int(length)) {
                    const uchar compFlag = static_cast<uchar>(chunkData[nullPos + 1]);
                    const uchar compMethod = static_cast<uchar>(chunkData[nullPos + 2]);
                    // Skip null-terminated language tag
                    int p = nullPos + 3;
                    while (p < int(length) && chunkData[p] != 0) ++p;
                    ++p; // skip null
                    // Skip null-terminated translated keyword
                    while (p < int(length) && chunkData[p] != 0) ++p;
                    ++p; // skip null
                    const int textLen = int(length) - p;
                    if (textLen >= 0 && p <= int(length)) {
                        if (compFlag == 1 && compMethod == 0) {
                            const uchar *zStream = reinterpret_cast<const uchar*>(chunkData + p);
                            const QByteArray decomp = inflateDataStream(zStream, textLen);
                            if (!decomp.isEmpty()) {
                                xmlStr = QString::fromUtf8(decomp);
                            }
                        } else if (compFlag == 0) {
                            xmlStr = QString::fromUtf8(chunkData + p, textLen);
                        }
                    }
                }

                if (!xmlStr.isEmpty()) {
                    auto decodeBase64Safe = [](QString b64Str) -> QByteArray {
                        b64Str = b64Str.trimmed();
                        if (b64Str.startsWith(QLatin1String("<![CDATA[")) && b64Str.endsWith(QLatin1String("]]>"))) {
                            b64Str = b64Str.mid(9, b64Str.length() - 12).trimmed();
                        }
                        QByteArray bytes = QByteArray::fromBase64(b64Str.toLatin1());
                        if (bytes.size() >= 8 && memcmp(bytes.constData(), "\x89PNG\r\n\x1a\n", 8) != 0 && memcmp(bytes.constData(), "GPAT", 4) != 0) {
                            QByteArray second = QByteArray::fromBase64(bytes);
                            if (!second.isEmpty() && (memcmp(second.constData(), "\x89PNG\r\n\x1a\n", 8) == 0 ||
                                                      memcmp(second.constData(), "GPAT", 4) == 0 ||
                                                      second.startsWith("\xff\xd8\xff") ||
                                                      second.startsWith("GIF") ||
                                                      second.startsWith("RIFF"))) {
                                return second;
                            }
                        }
                        return bytes;
                    };

                    // 1. Embedded base64 resources in Krita 5.0+ presets (<resources><resource ...>BASE64</resource></resources>)
                    static const QRegularExpression embResRe(
                        QStringLiteral("<resource\\b([^>]*?)>(.*?)</resource>"),
                        QRegularExpression::DotMatchesEverythingOption);
                    auto itEmb = embResRe.globalMatch(xmlStr);
                    while (itEmb.hasNext()) {
                        const auto m = itEmb.next();
                        const QString attrs = m.captured(1);
                        const QString b64 = m.captured(2).trimmed();
                        if (b64.isEmpty()) continue;
                        auto getAttr = [&attrs](const QString &attr) -> QString {
                            QRegularExpression r(QStringLiteral("%1\\s*=\\s*[\"']([^\"']+)[\"']").arg(attr), QRegularExpression::CaseInsensitiveOption);
                            auto mr = r.match(attrs);
                            return mr.hasMatch() ? mr.captured(1).trimmed() : QString();
                        };
                        const QString fn = getAttr(QStringLiteral("filename"));
                        const QString type = getAttr(QStringLiteral("type"));
                        if (!fn.isEmpty()) {
                            const QByteArray bytes = decodeBase64Safe(b64);
                            if (!bytes.isEmpty()) {
                                const bool isPattern = (type == QLatin1String("kis_patterns") || type == QLatin1String("patterns") || fn.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive));
                                const QString dir = (isPattern && !m_patternDir.isEmpty()) ? m_patternDir : m_brushDir;
                                if (!dir.isEmpty()) {
                                    const QString outPath = QDir(dir).filePath(QFileInfo(fn).fileName());
                                    QFile outF(outPath);
                                    if (outF.open(QIODevice::WriteOnly)) {
                                        outF.write(bytes);
                                        outF.close();
                                    }
                                }
                                loadSingleBrushResource(fn);
                            }
                        }
                    }

                    // 2. Embedded Texture/Pattern in Krita presets (<param name="Texture/Pattern/Pattern">BASE64</param>)
                    static const QRegularExpression patDataRe(
                        QStringLiteral("<param\\b[^>]*?name=[\"']Texture/Pattern/Pattern[\"'][^>]*>(?:<!\\[CDATA\\[)?([A-Za-z0-9+/=\\r\\n]+)(?:\\]\\]>)?</param>"),
                        QRegularExpression::CaseInsensitiveOption);
                    auto mrPat = patDataRe.match(xmlStr);
                    if (mrPat.hasMatch()) {
                        const QString b64 = mrPat.captured(1).trimmed();
                        const QByteArray patBytes = decodeBase64Safe(b64);
                        if (!patBytes.isEmpty()) {
                            static const QRegularExpression patNameRe(
                                QStringLiteral("<param\\b[^>]*?name=[\"']Texture/Pattern/Name[\"'][^>]*>(?:<!\\[CDATA\\[)?([^\\]<]+)(?:\\]\\]>)?</param>"),
                                QRegularExpression::CaseInsensitiveOption);
                            auto mrName = patNameRe.match(xmlStr);
                            QString patName = mrName.hasMatch() ? mrName.captured(1).trimmed() : QStringLiteral("embedded_pattern.png");
                            if (patName.isEmpty()) patName = QStringLiteral("embedded_pattern.png");

                            static const QRegularExpression patFileRe(
                                QStringLiteral("<param\\b[^>]*?name=[\"']Texture/Pattern/PatternFileName[\"'][^>]*>(?:<!\\[CDATA\\[)?([^\\]<]+)(?:\\]\\]>)?</param>"),
                                QRegularExpression::CaseInsensitiveOption);
                            auto mrFile = patFileRe.match(xmlStr);
                            const QString patFileName = mrFile.hasMatch() ? QFileInfo(mrFile.captured(1).trimmed()).fileName() : QString();

                            if (!m_patternDir.isEmpty()) {
                                const QString outPath = QDir(m_patternDir).filePath(QFileInfo(patName).fileName());
                                QFile outF(outPath);
                                if (outF.open(QIODevice::WriteOnly)) {
                                    outF.write(patBytes);
                                    outF.close();
                                }
                                if (!patFileName.isEmpty() && patFileName != patName) {
                                    const QString outPath2 = QDir(m_patternDir).filePath(patFileName);
                                    QFile outF2(outPath2);
                                    if (outF2.open(QIODevice::WriteOnly)) {
                                        outF2.write(patBytes);
                                        outF2.close();
                                    }
                                }
                            }
                            loadSingleBrushResource(patName);
                            if (!patFileName.isEmpty() && patFileName != patName) {
                                loadSingleBrushResource(patFileName);
                            }
                        }
                    }

                    // 3. Explicit filename="..." and pattern="..." XML attributes
                    static const QRegularExpression attrRe(
                        QStringLiteral("(?:filename|pattern)\\s*=\\s*[\"']([^\"']+)[\"']"),
                        QRegularExpression::CaseInsensitiveOption);
                    auto itAttr = attrRe.globalMatch(xmlStr);
                    while (itAttr.hasNext()) {
                        const QString rawVal = itAttr.next().captured(1).trimmed();
                        if (!rawVal.isEmpty()) {
                            loadSingleBrushResource(rawVal);
                        }
                    }

                    // 3.5 Required brush file params in Krita preset XML
                    static const QRegularExpression reqFileRe(
                        QStringLiteral("<param\\b[^>]*?name=[\"']requiredBrushFiles?(?:List)?[\"'][^>]*>(?:<!\\[CDATA\\[)?([^\\<]+?)(?:\\]\\]>)?</param>"),
                        QRegularExpression::CaseInsensitiveOption);
                    auto itReq = reqFileRe.globalMatch(xmlStr);
                    while (itReq.hasNext()) {
                        const QString reqVal = itReq.next().captured(1).trimmed();
                        if (!reqVal.isEmpty()) {
                            loadSingleBrushResource(reqVal);
                        }
                    }

                    // 4. Fallback regex to capture any brush resource file names in XML
                    static const QRegularExpression re(
                        QStringLiteral("([^\"'<>\r\n\t]+?\\.(?:gbr|gih|png|svg|pat|abr|jpg|jpeg))"),
                        QRegularExpression::CaseInsensitiveOption);
                    auto it = re.globalMatch(xmlStr);
                    while (it.hasNext()) {
                        const QString file = it.next().captured(1).trimmed();
                        loadSingleBrushResource(file);
                    }
                }
                break;
            }
        }
        idx += 12 + length;
    }
}

int ReverieCore::loadPatternResources(const QString &dirPath)
{
    m_patternDir = dirPath;
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    KisLocalStrokeResources *lr =
        dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());
    if (!lr) return 0;

    QDir dir(dirPath);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.pat") << QStringLiteral("*.png")
                      << QStringLiteral("*.jpg") << QStringLiteral("*.jpeg"),
        QDir::Files, QDir::Name);
    int loaded = 0;
    for (const QString &base : files) {
        const QString fullPath = dir.filePath(base);
        QFile f(fullPath);
        if (!f.open(QIODevice::ReadOnly)) continue;
        const QByteArray fileBytes = f.readAll();
        f.seek(0);
        KoPattern *pat = new KoPattern(fullPath);
        if (pat->loadFromDevice(&f, m_brushResources)) {
            pat->setFilename(base);
            pat->setName(base);
            const QString md5Hex = QString::fromLatin1(QCryptographicHash::hash(fileBytes, QCryptographicHash::Md5).toHex());
            pat->setMD5Sum(md5Hex);
            KoResourceSP patSp(pat);
            lr->addResource(patSp);
            if (base.toLower() != base) {
                KoResourceSP lowerSp(patSp->clone());
                if (lowerSp) {
                    lowerSp->setFilename(base.toLower());
                    lowerSp->setName(base.toLower());
                    lowerSp->setMD5Sum(md5Hex);
                    lr->addResource(lowerSp);
                }
            }
            if (base.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive)) {
                const QString noPat = base.left(base.length() - 4);
                if (!noPat.isEmpty()) {
                    KoResourceSP noPatSp(patSp->clone());
                    if (noPatSp) {
                        noPatSp->setFilename(noPat);
                        noPatSp->setName(noPat);
                        noPatSp->setMD5Sum(md5Hex);
                        lr->addResource(noPatSp);
                    }
                }
            } else {
                const QString withPat = base + QLatin1String(".pat");
                KoResourceSP withPatSp(patSp->clone());
                if (withPatSp) {
                    withPatSp->setFilename(withPat);
                    withPatSp->setName(withPat);
                    withPatSp->setMD5Sum(md5Hex);
                    lr->addResource(withPatSp);
                }
            }
            m_loadedResourceNames.insert(base);
            m_loadedResourceNames.insert(base.toLower());
            ++loaded;
        } else {
            delete pat;
        }
        f.close();
    }
    RPC_LOG("RPC loadPatternResources dir=%s loaded=%d", dirPath.toUtf8().constData(), loaded);
    return loaded;
}

int ReverieCore::loadBrushResources(const QString &dirPath)
{
    m_brushDir = dirPath;
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    QDir dir(dirPath);
    // Upfront only load lightweight brush files (< 1MB) and exclude .gih (which load on demand)
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.gbr") << QStringLiteral("*.png") << QStringLiteral("*.svg"),
        QDir::Files, QDir::Name);
    int loaded = 0;
    for (const QString &base : files) {
        if (m_loadedBrushes.contains(base)) {
            ++loaded;
            continue;
        }
        const QString fullPath = dir.filePath(base);
        QFileInfo fi(fullPath);
        if (fi.size() > 16 * 1024 * 1024) continue; // Skip excessively large brushes (>16MB); load on demand
        if (loadSingleBrushResource(base)) {
            ++loaded;
        }
    }
    RPC_LOG("RPC loadBrushResources dir=%s loaded=%d", dirPath.toUtf8().constData(), loaded);
    return loaded;
}

bool ReverieCore::loadBrushPreset(int index)
{
    if (index < 0 || index >= m_presets.size()) {
        return false;
    }
    registerPaintOps();
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    const QString path = m_presets[index].second;
    ensureBrushForPreset(path);
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return false;
    }
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    bool ok = preset->loadFromDevice(&f, m_brushResources);
    f.close();
    if (ok) {
        KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());
        const auto sideloaded = preset->sideLoadedResources(m_brushResources);
        bool addedSideloaded = false;
        for (const auto &loadRes : sideloaded) {
            KoResourceSP r = loadRes.resource();
            if (!r && loadRes.type() == KoResourceLoadResult::EmbeddedResource) {
                KoEmbeddedResource er = loadRes.embeddedResource();
                if (er.isValid()) {
                    const KoResourceSignature sig = er.signature();
                    const QByteArray data = er.data();
                    const bool isPattern = (sig.type == QLatin1String("patterns") || sig.type == QLatin1String("kis_patterns") || sig.filename.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive));
                    const QString targetDir = (isPattern && !m_patternDir.isEmpty()) ? m_patternDir : m_brushDir;
                    if (!targetDir.isEmpty() && !sig.filename.isEmpty()) {
                        const QString filePath = QDir(targetDir).filePath(QFileInfo(sig.filename).fileName());
                        if (!QFile::exists(filePath) && !data.isEmpty()) {
                            QFile outF(filePath);
                            if (outF.open(QIODevice::WriteOnly)) {
                                outF.write(data);
                                outF.close();
                            }
                        }
                    }
                    if (loadSingleBrushResource(sig.filename)) {
                        addedSideloaded = true;
                    }
                }
            } else if (r && lr) {
                lr->addResource(r);
                addedSideloaded = true;
            }
        }
        if (addedSideloaded) {
            QFile fReopen(path);
            if (fReopen.open(QIODevice::ReadOnly)) {
                preset->loadFromDevice(&fReopen, m_brushResources);
                fReopen.close();
            }
        }
    }
    RPC_LOG("RPC loadBrushPreset idx=%d path=%s ok=%d", index, path.toUtf8().constData(), ok);
    if (!ok) {
        return false;
    }
    m_presetIsEraserOverride = -1; // new preset: heuristic governs until UI asserts
    m_brushPreset = preset;
    m_brushPresetIndex = index;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setEraserMode(m_toolMode == ToolEraser);
        KisPaintOpSettingsSP s = m_brushPreset->settings();
        // 归一化压感开关：部分 Krita 预设（如 Basic-2_Opacity, Basic-4_Flow_Opacity 等）
        // 仅包含 OpacityUseCurve / FlowUseCurve / SizeUseCurve，缺失显式的 PressureOpacity / PressureFlow / PressureSize 属性。
        // Krita 的 KisOpacityOption / KisFlowOpacityOption 在读取配置时依赖 Pressure* 决定 isChecked，
        // 若缺失会导致压感曲线被跳过，恒定输出 1.0。
        if (!s->hasProperty("PressureOpacity") && s->getBool("OpacityUseCurve", false)) {
            const QString sensor = s->getString("OpacitySensor");
            if (sensor.isEmpty() || sensor.contains(QStringLiteral("id=\"pressure\""))) {
                s->setProperty("PressureOpacity", true);
            }
        }
        if (!s->hasProperty("PressureFlow") && s->getBool("FlowUseCurve", false)) {
            const QString sensor = s->getString("FlowSensor");
            if (sensor.isEmpty() || sensor.contains(QStringLiteral("id=\"pressure\""))) {
                s->setProperty("PressureFlow", true);
            }
        }
        if (!s->hasProperty("PressureSize") && s->getBool("SizeUseCurve", false)) {
            const QString sensor = s->getString("SizeSensor");
            if (sensor.isEmpty() || sensor.contains(QStringLiteral("id=\"pressure\""))) {
                s->setProperty("PressureSize", true);
            }
        }
        m_airbrushEnabled = s->getBool("PaintOpSettings/isAirbrushing",
                            s->getBool("AirbrushOption/isAirbrushing",
                            s->getBool("Airbrush/isChecked", false)));
        const double rate = s->getDouble("PaintOpSettings/rate",
                            s->getDouble("AirbrushOption/rate", 30.0));
        const bool colorRateChecked = s->getBool("ColorRate/isChecked", true);
        m_smudgeRate = colorRateChecked ? s->getDouble("ColorRateValue", s->getDouble("MixValue", 0.0)) : 0.0;
        const bool smudgeRateChecked = s->getBool("SmudgeRate/isChecked", true);
        m_smudgeLength = smudgeRateChecked ? s->getDouble("SmudgeRateValue", 0.5) : 0.0;

        const double presetSize = s->paintOpSize();
        if (presetSize > 0.0 && presetSize == presetSize) {
            m_brushSize = presetSize;
        }
        m_brushOpacity = qBound(0.0, s->getDouble("OpacityValue", 1.0), 1.0);
        m_brushFlow = qBound(0.0, s->getDouble("FlowValue", 1.0), 1.0);
        KisBrushBasedPaintOpSettings *bs = dynamic_cast<KisBrushBasedPaintOpSettings *>(s.data());
        if (bs) {
            if (bs->autoSpacingActive()) {
                m_brushSpacing = bs->autoSpacingCoeff();
            } else {
                const double sp = bs->spacing();
                if (sp > 0.0 && sp == sp) {
                    m_brushSpacing = sp;
                }
            }
        }
    }
    // Apply size / opacity / flow to the newly loaded preset.
    // If the caller (ViewModel) has user-customized saved parameters,
    // it will immediately follow with setBrush* to override these defaults.
    setBrushSize(m_brushSize);
    setBrushOpacity(m_brushOpacity);
    setBrushFlow(m_brushFlow);
    m_brushTipAsset.clear();
    m_tipOverridden = false; // 刚重新解析预设, 出厂笔尖天然在场
    // Diagnostics: is the preset's brush resolved to a real brush resource
    // or did it fall back to the default auto_brush (circle)?
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
    if (bs) {
        KisBrushSP b = bs->brush();
        if (b) {
            const QImage tip = b->brushTipImage();
            RPC_LOG("RPC brushRESOLVED file=%s tip=%dx%d valid=%d spacing=%.3f",
                    b->filename().toUtf8().constData(),
                    tip.width(), tip.height(), b->valid() ? 1 : 0,
                    (double)b->spacing());
        } else {
            RPC_LOG("RPC brushNULL");
        }
    } else {
        RPC_LOG("RPC brushNOCAST");
    }
    return true;
}

// 一次 .kpp 解析同时提取 defaults / paintOpId / tipFilename 三个只读值。
// 选择预设时 Kotlin 会在主线程连查这三个值 (原本各做一次完整解析:
// 读文件 + PNG zTXt 解压 + 正则扫描 + XML loadFromDevice), 是"换笔卡顿"
// 的主因; 这里解析一次后按 mtime+size 缓存 (updateKppFile 改写文件即失效)。
bool ReverieCore::ensurePresetInfo(int index, CachedPresetInfo &out)
{
    if (index < 0 || index >= m_presets.size()) {
        return false;
    }
    QMutexLocker lock(&m_presetInfoMutex);
    const QString path = m_presets[index].second;
    const QFileInfo fi(path);
    const qint64 mtimeMs = fi.exists() ? fi.lastModified().toMSecsSinceEpoch() : 0;
    const qint64 fsize = fi.size();
    auto it = m_presetInfoCache.find(index);
    if (it != m_presetInfoCache.end() && it->valid && it->mtimeMs == mtimeMs && it->fileSize == fsize) {
        out = *it;
        return true;
    }

    registerPaintOps();
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    ensureBrushForPreset(path);
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return false;
    }
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    bool ok = preset->loadFromDevice(&f, m_brushResources);
    f.close();
    if (ok) {
        KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());
        const auto sideloaded = preset->sideLoadedResources(m_brushResources);
        bool addedSideloaded = false;
        for (const auto &loadRes : sideloaded) {
            KoResourceSP r = loadRes.resource();
            if (!r && loadRes.type() == KoResourceLoadResult::EmbeddedResource) {
                KoEmbeddedResource er = loadRes.embeddedResource();
                if (er.isValid()) {
                    const KoResourceSignature sig = er.signature();
                    const QByteArray data = er.data();
                    const bool isPattern = (sig.type == QLatin1String("patterns") || sig.type == QLatin1String("kis_patterns") || sig.filename.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive));
                    const QString targetDir = (isPattern && !m_patternDir.isEmpty()) ? m_patternDir : m_brushDir;
                    if (!targetDir.isEmpty() && !sig.filename.isEmpty()) {
                        const QString filePath = QDir(targetDir).filePath(QFileInfo(sig.filename).fileName());
                        if (!QFile::exists(filePath) && !data.isEmpty()) {
                            QFile outF(filePath);
                            if (outF.open(QIODevice::WriteOnly)) {
                                outF.write(data);
                                outF.close();
                            }
                        }
                    }
                    if (loadSingleBrushResource(sig.filename)) {
                        addedSideloaded = true;
                    }
                }
            } else if (r && lr) {
                lr->addResource(r);
                addedSideloaded = true;
            }
        }
        if (addedSideloaded) {
            QFile fReopen(path);
            if (fReopen.open(QIODevice::ReadOnly)) {
                preset->loadFromDevice(&fReopen, m_brushResources);
                fReopen.close();
            }
        }
    }
    if (!ok || !preset->settings()) {
        return false;
    }
    KisPaintOpSettingsSP s = preset->settings();
    if (!s->hasProperty("PressureOpacity") && s->getBool("OpacityUseCurve", false)) {
        const QString sensor = s->getString("OpacitySensor");
        if (sensor.isEmpty() || sensor.contains(QStringLiteral("id=\"pressure\""))) {
            s->setProperty("PressureOpacity", true);
        }
    }
    if (!s->hasProperty("PressureFlow") && s->getBool("FlowUseCurve", false)) {
        const QString sensor = s->getString("FlowSensor");
        if (sensor.isEmpty() || sensor.contains(QStringLiteral("id=\"pressure\""))) {
            s->setProperty("PressureFlow", true);
        }
    }
    if (!s->hasProperty("PressureSize") && s->getBool("SizeUseCurve", false)) {
        const QString sensor = s->getString("SizeSensor");
        if (sensor.isEmpty() || sensor.contains(QStringLiteral("id=\"pressure\""))) {
            s->setProperty("PressureSize", true);
        }
    }

    CachedPresetInfo info;
    info.mtimeMs = mtimeMs;
    info.fileSize = fsize;

    // ---- defaults (原 brushPresetDefaults 的提取逻辑) ----
    double size = s->paintOpSize();
    if (!(size > 0.0) || size != size) {  // NaN / non-positive guard
        size = 20.0;
    }
    const double opacity = qBound(0.0, s->getDouble("OpacityValue", 1.0), 1.0);
    const double flow = qBound(0.0, s->getDouble("FlowValue", 1.0), 1.0);

    // Spacing
    double spacing = 0.15;
    KisBrushBasedPaintOpSettings *bs = dynamic_cast<KisBrushBasedPaintOpSettings *>(s.data());
    if (bs) {
        if (bs->autoSpacingActive()) {
            spacing = bs->autoSpacingCoeff();
        } else {
            const double sp = bs->spacing();
            if (sp > 0.0 && sp == sp) {
                spacing = sp;
            }
        }
    }
    if (!(spacing > 0.0) || spacing != spacing) {
        spacing = s->getDouble("spacing", 0.15);
    }
    if (!(spacing > 0.0) || spacing != spacing || spacing >= 0.75) {
        spacing = 0.15;
    }
    spacing = qBound(0.01, spacing, 2.5);

    // Airbrush
    const bool isAirbrush = s->getBool("PaintOpSettings/isAirbrushing",
                            s->getBool("AirbrushOption/isAirbrushing",
                            s->getBool("Airbrush/isChecked", false)));
    double airbrushRate = s->getDouble("PaintOpSettings/rate",
                          s->getDouble("AirbrushOption/rate", 30.0));
    if (!(airbrushRate >= 5.0) || airbrushRate != airbrushRate) {
        airbrushRate = 30.0;
    }

    // Smudge
    const bool colorRateChecked = s->getBool("ColorRate/isChecked", true);
    const double smudgeRate = colorRateChecked ? s->getDouble("ColorRateValue", s->getDouble("MixValue", 0.0)) : 0.0;
    const bool smudgeRateChecked = s->getBool("SmudgeRate/isChecked", true);
    const double smudgeLength = smudgeRateChecked ? s->getDouble("SmudgeRateValue", 0.5) : 0.0;

    // Angle & Scatter
    const double angle = s->paintOpAngle();
    const bool scatterChecked = s->getBool("PressureScatter", false) || s->getBool("Scatter/isChecked", false);
    const double scatter = scatterChecked ? s->paintOpScatter() : 0.0;

    // Softness, Ratio, Sharpness, Rotation
    const double softness = s->getDouble("SoftnessValue", s->getDouble("Softness", 0.5));
    const double ratio = s->getDouble("RatioValue", s->getDouble("Ratio", 1.0));
    const double sharpness = s->getDouble("SharpnessValue", s->getDouble("Sharpness", 0.0));
    const double rotation = s->getDouble("RotationValue", s->getDouble("Rotation", 0.0));

    // Dynamics
    bool hasPressureSize = true;
    if (s->hasProperty("PressureSize")) {
        hasPressureSize = s->getBool("PressureSize", true);
    } else if (s->hasProperty("SizeUseCurve")) {
        const QString sizeSensor = s->getString("SizeSensor");
        hasPressureSize = s->getBool("SizeUseCurve", false) && (sizeSensor.isEmpty() || sizeSensor.contains(QStringLiteral("id=\"pressure\"")));
    }
    const double pressureSize = hasPressureSize ? 1.0 : 0.0;

    bool hasPressureOpacity = false;
    if (s->hasProperty("PressureOpacity")) {
        hasPressureOpacity = s->getBool("PressureOpacity", false) && s->getBool("OpacityUseCurve", true);
    } else if (s->hasProperty("OpacityUseCurve")) {
        const QString opacitySensor = s->getString("OpacitySensor");
        hasPressureOpacity = s->getBool("OpacityUseCurve", false) && (opacitySensor.isEmpty() || opacitySensor.contains(QStringLiteral("id=\"pressure\"")));
    }
    const double pressureOpacity = hasPressureOpacity ? 1.0 : 0.0;

    bool hasPressureFlow = false;
    if (s->hasProperty("PressureFlow")) {
        hasPressureFlow = s->getBool("PressureFlow", false) && s->getBool("FlowUseCurve", true);
    } else if (s->hasProperty("FlowUseCurve")) {
        const QString flowSensor = s->getString("FlowSensor");
        hasPressureFlow = s->getBool("FlowUseCurve", false) && (flowSensor.isEmpty() || flowSensor.contains(QStringLiteral("id=\"pressure\"")));
    }
    const double pressureFlow = hasPressureFlow ? 1.0 : 0.0;
    const double followDirection = s->getBool("PressureRotation", false) ? 1.0 : 0.0;
    const double mirrorX = s->getBool("HorizontalMirrorEnabled", false) ? 1.0 : 0.0;
    const double mirrorY = s->getBool("VerticalMirrorEnabled", false) ? 1.0 : 0.0;
    const double antiAliasing = s->getBool("Antialiasing", s->getBool("antialiasEdges", true)) ? 1.0 : 0.0;

    info.defaults = {
        size, opacity, flow, spacing,
        isAirbrush ? 1.0 : 0.0, airbrushRate, smudgeRate, smudgeLength,
        angle, scatter, softness, ratio, sharpness, rotation,
        pressureSize, pressureOpacity, pressureFlow,
        followDirection, mirrorX, mirrorY, antiAliasing
    };

    // ---- paintOpId (原 brushPresetPaintOpId 的提取逻辑) ----
    info.paintOpId = preset->paintOp().id();

    // ---- tipFilename (原 brushPresetTipFilename 的提取逻辑) ----
    if (bs) {
        KisBrushSP b = bs->brush();
        if (b) {
            info.tipFilename = b->filename();
        }
    }

    info.compositeOp = s->effectivePaintOpCompositeOp();

    info.valid = true;
    m_presetInfoCache.insert(index, info);
    out = info;
    return true;
}

QVector<double> ReverieCore::brushPresetDefaults(int index)
{
    const QVector<double> defaultFallback = {
        20.0, 1.0, 1.0, 0.15,
        0.0, 30.0, 0.5, 0.5,
        0.0, 0.0, 0.5, 1.0, 0.0, 0.0,
        1.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    };
    CachedPresetInfo info;
    if (!ensurePresetInfo(index, info) || info.defaults.isEmpty()) {
        return defaultFallback;
    }
    return info.defaults;
}

QString ReverieCore::brushPresetPaintOpId(int index)
{
    CachedPresetInfo info;
    if (!ensurePresetInfo(index, info) || info.paintOpId.isEmpty()) {
        return QStringLiteral("paintbrush");
    }
    return info.paintOpId;
}

QString ReverieCore::brushPresetCompositeOp(int index)
{
    CachedPresetInfo info;
    if (!ensurePresetInfo(index, info) || info.compositeOp.isEmpty()) {
        return QStringLiteral("normal");
    }
    return info.compositeOp;
}

QString ReverieCore::currentBrushPaintOpId() const
{
    if (m_brushPreset) {
        const QString id = m_brushPreset->paintOp().id();
        if (!id.isEmpty()) return id;
    }
    return QStringLiteral("paintbrush");
}

QString ReverieCore::brushPresetTipFilename(int index)
{
    CachedPresetInfo info;
    if (!ensurePresetInfo(index, info)) {
        return QString();
    }
    return info.tipFilename;
}

int ReverieCore::brushPresetCount() const
{
    return m_presets.size();
}

QString ReverieCore::brushPresetName(int index) const
{
    if (index < 0 || index >= m_presets.size()) {
        return QString();
    }
    return m_presets[index].first;
}

QString ReverieCore::brushPresetPath(int index) const
{
    if (index < 0 || index >= m_presets.size()) {
        return QString();
    }
    return m_presets[index].second;
}

QByteArray ReverieCore::brushPresetThumbData(int index) const
{
    // The .kpp files ARE PNG thumbnails with an embedded "preset" zTXt chunk;
    // return the raw bytes so the UI can decode them directly.
    if (index < 0 || index >= m_presets.size()) {
        return QByteArray();
    }
    QFile f(m_presets[index].second);
    if (!f.open(QIODevice::ReadOnly)) {
        return QByteArray();
    }
    return f.readAll();
}

void ReverieCore::setBrushSize(qreal v)
{
    m_brushSize = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpSize(v);
    }
}

void ReverieCore::setBrushOpacity(qreal v)
{
    m_brushOpacity = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpOpacity(v);
    }
}

void ReverieCore::setBrushFlow(qreal v)
{
    m_brushFlow = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpFlow(v);
    }
}

// Smudge engine parameters. Key names verified against bundled presets:
// k)_Blender_Basic.kpp exposes SmudgeRateValue (length) and ColorRateValue
// (color mixing rate), paintop="colorsmudge". The rate is written to both
// ColorRateValue and legacy MixValue so new-generation presets (reading
// ColorRateValue) and old-generation ones (reading MixValue) both pick it up.
void ReverieCore::setBrushSmudgeRate(qreal v)
{
    m_smudgeRate = v;
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    s->setProperty("ColorRateValue", v);
    s->setProperty("MixValue", v); // legacy key for older-generation presets
    s->setProperty("ColorRate/strengthValue", v);
    if (v <= 0.0001) {
        s->setProperty("ColorRate/isChecked", false);
    } else {
        s->setProperty("ColorRate/isChecked", true);
    }
}

void ReverieCore::setBrushSmudgeLength(qreal v)
{
    m_smudgeLength = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        KisPaintOpSettingsSP s = m_brushPreset->settings();
        s->setProperty("SmudgeRateValue", v);
        s->setProperty("SmudgeRate/strengthValue", v);
        if (v <= 0.0001) {
            s->setProperty("SmudgeRate/isChecked", false);
        } else {
            s->setProperty("SmudgeRate/isChecked", true);
        }
    }
}

// Airbrush mode. Krita keys (kis_paintop_settings.h):
//   AIRBRUSH_ENABLED = "PaintOpSettings/isAirbrushing" (bool)
//   AIRBRUSH_RATE    = "PaintOpSettings/rate" (dabs per second, interval=1000/rate)
void ReverieCore::setBrushAirbrush(bool enabled, qreal rate)
{
    m_airbrushEnabled = enabled;
    m_airbrushRate = rate >= 5.0 ? rate : 30.0;
    if (m_brushPreset && m_brushPreset->settings()) {
        KisPaintOpSettingsSP s = m_brushPreset->settings();
        s->setProperty("PaintOpSettings/isAirbrushing", enabled);
        s->setProperty("AirbrushOption/isAirbrushing", enabled);
        s->setProperty("Airbrush/isChecked", enabled);
        s->setProperty("PaintOpSettings/rate", m_airbrushRate);
        s->setProperty("AirbrushOption/rate", m_airbrushRate);
    }
}

void ReverieCore::setBrushSpacing(qreal v)
{
    m_brushSpacing = v;
    if (!m_brushPreset || !m_brushPreset->settings()) {
        return;
    }
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(s.data());
    if (!bs) {
        // 非笔刷型引擎 (deform / spray / particle ...) 没有 brush_definition,
        // 保持原有的 settings 属性通道, 不动它的行为。
        s->setProperty("spacing", v);
        return;
    }
    if (bs->autoSpacingActive()) {
        // 与 Krita 官方 KisBrushBasedPaintOpSettings 完全对齐:
        // 自动间距开启时, 间距参数控制的是 autoSpacingCoeff, 保持 autoSpacingActive(true)!
        // 严禁设为 false, 否则大尺寸下间距无法根据 sqrt(size) 自适应, 必然变成散点珠串!
        bs->setAutoSpacing(true, v);
    } else {
        bs->setSpacing(v);
    }
    // 不再写 SpacingValue: 它是 KisSpacingOption 的 extraScale 乘数, 会与
    // brush spacing 相乘把间距再缩一次 (PressureSpacing=true 的预设尤其明显)。
}

void ReverieCore::setBrushAngle(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpAngle(v);
    }
}

void ReverieCore::setBrushScatter(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        KisPaintOpSettingsSP s = m_brushPreset->settings();
        s->setPaintOpScatter(v);
        // KisPaintOpSettings::setPaintOpScatter early-returns if "PressureScatter" property is missing.
        // Directly write both Krita 4 and 5 scattering keys to ensure scatter works on any preset.
        const bool active = (v > 0.001);
        s->setProperty("PressureScatter", active);
        s->setProperty("Scatter/isChecked", active);
        s->setProperty("Scatter/strengthValue", v);
        s->setProperty("Scattering/Amount", v);
        s->setProperty("ScatterValue", v);
        s->setProperty("Scattering/AxisX", true);
        s->setProperty("Scattering/AxisY", true);
    }
}

void ReverieCore::setBrushFade(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpFade(v);
    }
}

void ReverieCore::setBrushSoftness(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("SoftnessValue", v);
    }
}

void ReverieCore::setBrushRatio(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("RatioValue", v);
    }
}

void ReverieCore::setBrushSharpness(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("SharpnessValue", v);
    }
}

void ReverieCore::setBrushRotation(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("RotationValue", v);
    }
}

void ReverieCore::setToolMode(int mode)
{
    m_toolMode = ToolMode(mode);
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setEraserMode(m_toolMode == ToolEraser);
    }
}

void ReverieCore::setPresetIsEraser(bool eraser)
{
    m_presetIsEraserOverride = eraser ? 1 : 0;
}

void ReverieCore::setBrushCompositeOp(const QString &op)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpCompositeOp(op);
    }
}

bool ReverieCore::setBrushTipAsset(const QString &assetName)
{
    m_brushTipAsset = assetName;
    if (!m_brushPreset || !m_brushPreset->settings()) {
        return false;
    }
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
    if (!bs) {
        return false;
    }
    if (assetName.isEmpty()) {
        // Restore factory brush tip from the original .kpp preset file.
        // Skip the re-parse when no tip override is in effect: loadBrushPreset
        // always parses a fresh preset whose settings already carry the
        // factory brush, so restoring would re-read the very same file.
        if (!m_tipOverridden) {
            RPC_LOG("RPC setBrushTipAsset: no override in effect, factory tip already active (preset %d)", m_brushPresetIndex);
            return true;
        }
        if (m_brushPresetIndex >= 0 && m_brushPresetIndex < m_presets.size()) {
            QFile f(m_presets[m_brushPresetIndex].second);
            if (f.open(QIODevice::ReadOnly)) {
                KisPaintOpPresetSP originalPreset(new KisPaintOpPreset(m_presets[m_brushPresetIndex].first));
                if (originalPreset->loadFromDevice(&f, m_brushResources)) {
                    KisBrushBasedPaintOpSettings *origBs =
                        dynamic_cast<KisBrushBasedPaintOpSettings *>(originalPreset->settings().data());
                    if (origBs && origBs->brush()) {
                        KisBrushOptionProperties prop;
                        prop.readOptionSetting(origBs, m_brushResources, origBs->canvasResourcesInterface());
                        prop.writeOptionSetting(bs);
                        m_tipOverridden = false;
                        RPC_LOG("RPC setBrushTipAsset: restored factory brush tip for preset %d", m_brushPresetIndex);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    KisBrushSP brush = m_loadedBrushes.value(assetName);
    if (!brush) {
        loadSingleBrushResource(assetName);
        brush = m_loadedBrushes.value(assetName);
    }
    if (!brush) {
        for (auto it = m_loadedBrushes.begin(); it != m_loadedBrushes.end(); ++it) {
            if (it.key().compare(assetName, Qt::CaseInsensitive) == 0 ||
                it.key().startsWith(assetName, Qt::CaseInsensitive) ||
                (it.value() && it.value()->name().compare(assetName, Qt::CaseInsensitive) == 0)) {
                brush = it.value();
                break;
            }
        }
    }
    if (!brush) {
        RPC_LOG("RPC setBrushTipAsset not found: %s", assetName.toUtf8().constData());
        return false;
    }
    KisBrushOptionProperties prop;
    prop.readOptionSetting(bs, m_brushResources, bs->canvasResourcesInterface());
    prop.setBrush(brush);
    prop.writeOptionSetting(bs);
    m_tipOverridden = true;
    RPC_LOG("RPC setBrushTipAsset SUCCESS: %s", assetName.toUtf8().constData());
    return true;
}

void ReverieCore::setBrushPressureDynamics(bool enabled, qreal sizeStrength, qreal opacityStrength, qreal flowStrength, int curveType)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();

    // Invalidate size curve cache for cursor ring
    {
        QMutexLocker locker(&m_sizeCurveMutex);
        m_sizeCurveOwner = nullptr;
    }

    if (!enabled) {
        s->setProperty("PressureSize", false);
        s->setProperty("SizeUseCurve", false);
        s->setProperty("PressureOpacity", false);
        s->setProperty("OpacityUseCurve", false);
        s->setProperty("PressureFlow", false);
        s->setProperty("FlowUseCurve", false);
        return;
    }

    QString curveStr;
    switch (curveType) {
    case 1: // Soft
        curveStr = QStringLiteral("0,0;0.25,0.5;0.75,0.9;1,1;");
        break;
    case 2: // Hard
        curveStr = QStringLiteral("0,0;0.25,0.1;0.75,0.5;1,1;");
        break;
    case 3: // S-curve
        curveStr = QStringLiteral("0,0;0.25,0.1;0.75,0.9;1,1;");
        break;
    case 0: // Linear
    default:
        curveStr = QStringLiteral("0,0;1,1;");
        break;
    }

    const QString sensorXml = QStringLiteral("<!DOCTYPE params><params id=\"pressure\"><curve>%1</curve></params>").arg(curveStr);

    const bool useSize = (sizeStrength > 0.001);
    s->setProperty("PressureSize", useSize);
    s->setProperty("SizeUseCurve", useSize);
    s->setProperty("SizeValue", sizeStrength);
    if (useSize) {
        s->setProperty("SizeSensor", sensorXml);
    }

    const bool useOpacity = (opacityStrength > 0.001);
    s->setProperty("PressureOpacity", useOpacity);
    s->setProperty("OpacityUseCurve", useOpacity);
    if (useOpacity) {
        s->setProperty("OpacitySensor", sensorXml);
    }

    const bool useFlow = (flowStrength > 0.001);
    s->setProperty("PressureFlow", useFlow);
    s->setProperty("FlowUseCurve", useFlow);
    if (useFlow) {
        s->setProperty("FlowSensor", sensorXml);
    }
}

void ReverieCore::setBrushOptionDynamics(const QString &optionName, bool enabled, const QString &sensorId, const QString &curvePoints, qreal strength)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();

    QString opt = optionName.trimmed();
    if (opt.isEmpty()) return;
    opt = opt.left(1).toUpper() + opt.mid(1);

    if (opt.compare("Size", Qt::CaseInsensitive) == 0) {
        QMutexLocker locker(&m_sizeCurveMutex);
        m_sizeCurveOwner = nullptr;
    }

    const QString pressKey = QStringLiteral("Pressure%1").arg(opt);
    const QString curveKey = QStringLiteral("%1UseCurve").arg(opt);
    const QString valKey   = QStringLiteral("%1Value").arg(opt);
    const QString sensorKey = QStringLiteral("%1Sensor").arg(opt);

    if (!enabled) {
        s->setProperty(pressKey.toLatin1().constData(), false);
        s->setProperty(curveKey.toLatin1().constData(), false);
        return;
    }

    s->setProperty(pressKey.toLatin1().constData(), true);
    s->setProperty(curveKey.toLatin1().constData(), true);
    s->setProperty(valKey.toLatin1().constData(), strength);

    QString formattedCurve = curvePoints.trimmed();
    if (!formattedCurve.isEmpty() && !formattedCurve.endsWith(';')) {
        formattedCurve.append(';');
    }
    if (formattedCurve.isEmpty()) {
        formattedCurve = QStringLiteral("0,0;1,1;");
    }

    QString sensor = sensorId.trimmed().toLower();
    if (sensor.isEmpty()) {
        sensor = QStringLiteral("pressure");
    }

    QString sensorXml;
    if (sensor == QStringLiteral("drawingangle")) {
        sensorXml = QStringLiteral("<!DOCTYPE params><params fanCornersStep=\"30\" fanCornersEnabled=\"0\" angleOffset=\"0\" id=\"drawingangle\"><curve>%1</curve></params>").arg(formattedCurve);
    } else {
        sensorXml = QStringLiteral("<!DOCTYPE params><params id=\"%1\"><curve>%2</curve></params>").arg(sensor, formattedCurve);
    }

    s->setProperty(sensorKey.toLatin1().constData(), sensorXml);
}

void ReverieCore::setBrushFollowDirection(bool enabled)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    s->setProperty("PressureRotation", enabled);
    s->setProperty("RotationUseCurve", enabled);
    if (enabled) {
        s->setProperty("RotationSensor", QStringLiteral("<!DOCTYPE params><params fanCornersStep=\"30\" fanCornersEnabled=\"0\" angleOffset=\"0\" id=\"drawingangle\"><curve>0,0;1,1;</curve></params>"));
        s->setProperty("RotationValue", 1.0);
    }
}

void ReverieCore::setBrushJitter(qreal jitterAngle, qreal jitterSize)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    if (jitterAngle > 0.001) {
        s->setProperty("PressureRotation", true);
        s->setProperty("RotationUseCurve", true);
        s->setProperty("RotationValue", jitterAngle);
        s->setProperty("RotationSensor", QStringLiteral("<!DOCTYPE params><params id=\"fuzzy\"><curve>0,0;1,1;</curve></params>"));
    }
    if (jitterSize > 0.001) {
        s->setProperty("PressureSize", true);
        s->setProperty("SizeUseCurve", true);
        s->setProperty("SizeValue", jitterSize);
        s->setProperty("SizeSensor", QStringLiteral("<!DOCTYPE params><params id=\"fuzzy_per_dab\"><curve>0,0;1,1;</curve></params>"));
    }
}

void ReverieCore::setBrushMirror(bool flipX, bool flipY)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    s->setProperty("HorizontalMirrorEnabled", flipX);
    s->setProperty("VerticalMirrorEnabled", flipY);
}

void ReverieCore::setBrushAntiAliasing(int level)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    const bool aa = (level > 0);
    s->setProperty("Antialiasing", aa);
    s->setProperty("antialiasEdges", aa);
}

void ReverieCore::setBrushTexture(bool enabled, qreal scale, qreal strength, const QString &mode, const QString &patternName)
{
    if (!m_brushPreset || !m_brushPreset->settings()) return;
    KisPaintOpSettingsSP s = m_brushPreset->settings();
    s->setProperty("Texture/Pattern/Enabled", enabled);
    s->setProperty("PressureTexture/Strength/", enabled);
    s->setProperty("Texture/Pattern/Scale", scale);
    s->setProperty("Texture/Pattern/Strength", strength);
    QString modeCode = QStringLiteral("0");
    if (mode.compare(QStringLiteral("subtract"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("1");
    else if (mode.compare(QStringLiteral("darken"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("4");
    else if (mode.compare(QStringLiteral("overlay"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("5");
    else if (mode.compare(QStringLiteral("dodge"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("6");
    else if (mode.compare(QStringLiteral("burn"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("7");
    else if (mode.compare(QStringLiteral("hard_light"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("10");
    else if (mode.compare(QStringLiteral("soft_light"), Qt::CaseInsensitive) == 0) modeCode = QStringLiteral("11");
    s->setProperty("Texture/Pattern/TexturingMode", modeCode);
    if (!patternName.isEmpty()) {
        s->setProperty("Texture/Pattern/PatternFileName", patternName);
        s->setProperty("Texture/Pattern/Name", patternName);
    }
}

bool ReverieCore::scratchpadStart(int w, int h)
{
    if (w <= 0 || h <= 0) return false;
    const KoColorSpace *cs = (m_document && m_document->colorSpace())
        ? m_document->colorSpace()
        : KoColorSpaceRegistry::instance()->rgb8();
    if (!m_scratchpadDev || m_scratchpadWidth != w || m_scratchpadHeight != h) {
        m_scratchpadDev = new KisPaintDevice(cs);
        m_scratchpadWidth = w;
        m_scratchpadHeight = h;
    }
    m_scratchpadDev->clear();
    if (m_scratchpadPainter) {
        m_scratchpadPainter->end();
        delete m_scratchpadPainter;
        m_scratchpadPainter = nullptr;
    }
    if (m_scratchpadDistInfo) {
        delete m_scratchpadDistInfo;
        m_scratchpadDistInfo = nullptr;
    }
    m_scratchpadStrokeActive = false;
    return true;
}

bool ReverieCore::scratchpadStrokeStart(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation)
{
    if (!m_scratchpadDev || !m_brushPreset) return false;
    if (m_scratchpadPainter) {
        m_scratchpadPainter->end();
        delete m_scratchpadPainter;
        m_scratchpadPainter = nullptr;
    }
    if (m_scratchpadDistInfo) {
        delete m_scratchpadDistInfo;
        m_scratchpadDistInfo = nullptr;
    }

    m_scratchpadPainter = new KisPainter(m_scratchpadDev);
    KisNodeSP node = (!m_layers.isEmpty() && m_layers.first().node) ? KisNodeSP(m_layers.first().node) : KisNodeSP();
    m_scratchpadPainter->setPaintOpPreset(m_brushPreset, node, m_document);

    const KoColorSpace *cs = m_scratchpadDev->colorSpace();
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) qColor = Qt::black;
    m_scratchpadPainter->setPaintColor(KoColor(qColor, cs));

    QColor qBgColor(m_brushSecondaryColor);
    if (!qBgColor.isValid()) qBgColor = Qt::white;
    m_scratchpadPainter->setBackgroundColor(KoColor(qBgColor, cs));

    m_scratchpadPainter->setOpacityF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    QString compOp = m_brushPreset->settings() ? m_brushPreset->settings()->effectivePaintOpCompositeOp() : QStringLiteral("normal");
    if (m_toolMode == ToolEraser || (m_presetIsEraserOverride == 1)) {
        compOp = QStringLiteral("erase");
    }
    m_scratchpadPainter->setCompositeOpId(compOp.isEmpty() ? QStringLiteral("normal") : compOp);

    const QPointF pt(x, y);
    m_scratchpadDistInfo = new KisDistanceInformation(pt, 0.0);
    KisPaintInformation pi(pt, pressure, tiltX, tiltY, rotation);
    m_scratchpadPainter->paintAt(pi, m_scratchpadDistInfo);

    m_scratchpadLastSample = StrokeSample{pt, pressure, tiltX, tiltY, rotation, 0.0};
    m_scratchpadStrokeActive = true;
    return true;
}

bool ReverieCore::scratchpadStrokeMove(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation)
{
    if (!m_scratchpadStrokeActive || !m_scratchpadPainter || !m_scratchpadDistInfo) return false;
    const QPointF pt(x, y);
    KisPaintInformation pi1(m_scratchpadLastSample.imgPos, m_scratchpadLastSample.pressure, m_scratchpadLastSample.tiltX, m_scratchpadLastSample.tiltY, m_scratchpadLastSample.rotation);
    KisPaintInformation pi2(pt, pressure, tiltX, tiltY, rotation);
    m_scratchpadPainter->paintLine(pi1, pi2, m_scratchpadDistInfo);
    m_scratchpadLastSample = StrokeSample{pt, pressure, tiltX, tiltY, rotation, 0.0};
    return true;
}

void ReverieCore::scratchpadStrokeEnd()
{
    if (m_scratchpadPainter) {
        m_scratchpadPainter->end();
        delete m_scratchpadPainter;
        m_scratchpadPainter = nullptr;
    }
    if (m_scratchpadDistInfo) {
        delete m_scratchpadDistInfo;
        m_scratchpadDistInfo = nullptr;
    }
    m_scratchpadStrokeActive = false;
}

void ReverieCore::scratchpadClear()
{
    if (m_scratchpadDev) {
        m_scratchpadDev->clear();
    }
}

bool ReverieCore::scratchpadRender(quint8 *buffer, int w, int h, int stride)
{
    if (!m_scratchpadDev || !buffer || w <= 0 || h <= 0 || stride < w * 4) return false;
    const int rw = qMin(w, m_scratchpadWidth);
    const int rh = qMin(h, m_scratchpadHeight);
    QByteArray tmp(rw * rh * 4, 0);
    m_scratchpadDev->readBytes(reinterpret_cast<quint8 *>(tmp.data()), 0, 0, rw, rh);
    PixelAlpha::toDisplayRows(reinterpret_cast<const uint8_t *>(tmp.constData()), rw * 4, buffer, stride, rw, rh);
    return true;
}

void ReverieCore::scratchpadEnd()
{
    if (m_scratchpadPainter) {
        m_scratchpadPainter->end();
        delete m_scratchpadPainter;
        m_scratchpadPainter = nullptr;
    }
    if (m_scratchpadDistInfo) {
        delete m_scratchpadDistInfo;
        m_scratchpadDistInfo = nullptr;
    }
    m_scratchpadDev = nullptr;
    m_scratchpadWidth = 0;
    m_scratchpadHeight = 0;
    m_scratchpadStrokeActive = false;
}


