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
#include "StrokeTimeContract.h"

#include <KisInterstrokeDataFactory.h>
#include <kundo2command.h>

#include <QRegularExpression>
#include <QBuffer>
#include <QUrl>
#include <algorithm>
#include <cmath>
#include <QtEndian>
#include <zlib.h>
#include <QCryptographicHash>

#include <kis_abr_brush_collection.h>
#include <kis_abr_brush.h>
#include <KoEmbeddedResource.h>
// calcAutoSpacing(): 自动间距 (useAutoSpacing="1") 的生效值计算, 与
// KisPaintOpUtils::effectiveSpacing 内部用的是同一个公式。
#include <kis_paintop_utils.h>

// 把请求的间距 requested 写进笔刷型预设, 大直径湿笔 (colorsmudge) 加一道像素步长下限。
//
// 部分湿笔预设间距极小: Krita 自带湿笔多为 autoSpacing 且系数仅 0.05~0.08, 按
// KisPaintOpUtils::effectiveSpacing 的 coeff * sqrt(D), 400px 时步长只有 ~1px, 一帧划过
// 300px 就是 300 个 400x400 的 colorsmudge dab, paintLine 远超帧预算。
// 下限按实际像素步长统一封堵线性间距与 autoSpacing 两条通道:
//   Smearing (SmudgeRateMode 0): step >= max(4px, 0.055 * D)
//   Dulling  (SmudgeRateMode 1): step >= max(4px, 0.080 * D)
// 只抬不降, 且每次都从 requested 重新计算 (不回写 m_brushSpacing): 尺寸调回 100px 以下
// 时恢复预设/用户原值, 不会把抬高的间距棘轮式留在小尺寸上。
static bool isColorSmudgePreset(const KisPaintOpPresetSP &preset)
{
    return preset && preset->paintOp().id() == QLatin1String("colorsmudge");
}

static void writeBrushSpacing(KisBrushBasedPaintOpSettings *bs, const KisPaintOpPresetSP &preset,
                              qreal brushSize, qreal requested)
{
    qreal minPxStep = 0.0;
    if (isColorSmudgePreset(preset) && brushSize >= 100.0) {
        const qreal minRatio = (preset->settings()->getInt("SmudgeRateMode", 0) == 1) ? 0.08 : 0.055;
        minPxStep = qMax<qreal>(4.0, brushSize * minRatio);
    }
    if (bs->autoSpacingActive()) {
        // 自动间距开启时间距参数控制 autoSpacingCoeff, 必须保持 autoSpacingActive(true),
        // 否则大尺寸下间距不再按 sqrt(size) 自适应, 变成散点珠串 (与 Krita 一致)。
        const qreal sqrtD = std::sqrt(qMax<qreal>(1.0, brushSize));
        bs->setAutoSpacing(true, qMax(requested, minPxStep / sqrtD));
    } else {
        bs->setSpacing(minPxStep > 0.0 ? qMax(requested, minPxStep / brushSize) : requested);
    }
}

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
        krita_register_mypaint_paintop();
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
    const QStringList files = dir.entryList(QStringList() << QStringLiteral("*.kpp") << QStringLiteral("*.myb"),
                                            QDir::Files, QDir::Name);
    m_presets.clear();
    for (const QString &f : files) {
        QString name = f;
        if (name.endsWith(QLatin1String(".kpp"), Qt::CaseInsensitive) ||
            name.endsWith(QLatin1String(".myb"), Qt::CaseInsensitive)) {
            name.chop(4);  // strip extension (.kpp or .myb)
        }
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

    const bool inBrushDir = !m_brushDir.isEmpty() &&
        QFile::exists(QDir(m_brushDir).filePath(bareName));
    const bool inPatternDir = !m_patternDir.isEmpty() &&
        QFile::exists(QDir(m_patternDir).filePath(bareName));
    const bool isBrush = inBrushDir || (!inPatternDir && (
        bareName.endsWith(QLatin1String(".gbr"), Qt::CaseInsensitive) ||
        bareName.endsWith(QLatin1String(".gih"), Qt::CaseInsensitive) ||
        bareName.endsWith(QLatin1String(".png"), Qt::CaseInsensitive) ||
        bareName.endsWith(QLatin1String(".svg"), Qt::CaseInsensitive) ||
        bareName.endsWith(QLatin1String(".abr"), Qt::CaseInsensitive)));

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
                    QFile origFile(fullPath);
                    if (origFile.open(QIODevice::ReadOnly)) {
                        const QByteArray origBytes = origFile.readAll();
                        origFile.close();
                        if (!origBytes.isEmpty()) {
                            const QString md5Hex = QString::fromLatin1(QCryptographicHash::hash(origBytes, QCryptographicHash::Md5).toHex());
                            res->setMD5Sum(md5Hex);
                        }
                    }
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
                        if (!fn.isEmpty()) {
                            loadSingleBrushResource(fn);
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

bool ReverieCore::loadBrushPresetCommon(const QString &path, const QString &name, int index)
{
    registerPaintOps();
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    ensureBrushForPreset(path);
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return false;
    }
    KisPaintOpPresetSP preset;
    if (path.endsWith(QLatin1String(".myb"), Qt::CaseInsensitive)) {
        preset = KisPaintOpPresetSP(krita_create_mypaint_preset(path));
    } else {
        preset = KisPaintOpPresetSP(new KisPaintOpPreset(name.isEmpty() ? path : name));
    }
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
    RPC_LOG("RPC loadBrushPresetCommon idx=%d path=%s ok=%d", index, path.toUtf8().constData(), ok);
    if (!ok) {
        return false;
    }
    m_presetIsEraserOverride = -1; // new preset: heuristic governs until UI asserts
    m_brushPreset = preset;
    m_brushPresetIndex = index;
    m_currentPresetPath = path;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setEraserMode(m_toolMode == ToolEraser);
        KisPaintOpSettingsSP s = m_brushPreset->settings();
        if (!s->hasProperty("PressureOpacity") && s->getBool("OpacityUseCurve", false)) {
            s->setProperty("PressureOpacity", true);
        }
        if (!s->hasProperty("PressureFlow") && s->getBool("FlowUseCurve", false)) {
            s->setProperty("PressureFlow", true);
        }
        if (!s->hasProperty("PressureSize") && s->getBool("SizeUseCurve", false)) {
            s->setProperty("PressureSize", true);
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
        m_brushAngle = s->paintOpAngle();
        m_brushRotation = 0.0;
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
            const qreal parsed = bs->autoSpacingActive() ? bs->autoSpacingCoeff() : bs->spacing();
            if (isColorSmudgePreset(m_brushPreset) && parsed > 0.0 && std::isfinite(parsed)) {
                writeBrushSpacing(bs, m_brushPreset, m_brushSize, parsed);
            }
        }
    }
    setBrushSize(m_brushSize);
    setBrushOpacity(m_brushOpacity);
    setBrushFlow(m_brushFlow);
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

bool ReverieCore::loadBrushPreset(int index)
{
    if (index < 0 || index >= m_presets.size()) {
        return false;
    }
    return loadBrushPresetCommon(m_presets[index].second, m_presets[index].first, index);
}

bool ReverieCore::loadBrushPresetFile(const QString &path)
{
    if (path.isEmpty()) {
        return false;
    }
    const QString name = QFileInfo(path).completeBaseName();
    return loadBrushPresetCommon(path, name, -1);
}

bool ReverieCore::currentTipResolved()
{
    if (!m_brushPreset || !m_brushPreset->settings()) {
        return false;
    }
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
    if (bs) {
        KisBrushSP b = bs->brush();
        if (b && b->valid()) {
            const QImage tip = b->brushTipImage();
            return !tip.isNull() && tip.width() > 0 && tip.height() > 0;
        }
    }
    return false;
}

bool ReverieCore::beginPresetOverrideSnapshot()
{
    if (!m_brushPreset) {
        return false;
    }
    m_overrideSnapshotPreset = m_brushPreset;
    m_overrideSnapshotIndex = m_brushPresetIndex;
    m_overrideSnapshotSize = m_brushSize;
    m_overrideSnapshotOpacity = m_brushOpacity;
    m_overrideSnapshotFlow = m_brushFlow;
    m_overrideSnapshotSpacing = m_brushSpacing;
    m_overrideSnapshotAngle = m_brushAngle;
    m_overrideSnapshotRotation = m_brushRotation;
    m_overrideSnapshotSmudgeRate = m_smudgeRate;
    m_overrideSnapshotSmudgeLength = m_smudgeLength;
    m_overrideSnapshotAirbrush = m_airbrushEnabled;
    m_overrideSnapshotEraserFlag = m_presetIsEraserOverride;
    m_overrideSnapshotToolMode = m_toolMode;
    m_overrideSnapshotPresetPath = m_currentPresetPath;
    m_overrideSnapshotCompositeOp = m_brushPreset->settings() ? m_brushPreset->settings()->paintOpCompositeOp() : QString();
    RPC_LOG("RPC overrideSnapshot begin idx=%d path=%s", m_brushPresetIndex, m_currentPresetPath.toUtf8().constData());
    return true;
}

bool ReverieCore::endPresetOverrideSnapshot()
{
    if (!m_overrideSnapshotPreset) {
        return false;
    }
    // 先取出并清空快照 (消费一次即失效, 防止旧快照被误用)
    const KisPaintOpPresetSP preset = m_overrideSnapshotPreset;
    const int index = m_overrideSnapshotIndex;
    const QString presetPath = m_overrideSnapshotPresetPath;
    const qreal size = m_overrideSnapshotSize;
    const qreal opacity = m_overrideSnapshotOpacity;
    const qreal flow = m_overrideSnapshotFlow;
    const qreal spacing = m_overrideSnapshotSpacing;
    const qreal angle = m_overrideSnapshotAngle;
    const qreal rotation = m_overrideSnapshotRotation;
    const qreal smudgeRate = m_overrideSnapshotSmudgeRate;
    const qreal smudgeLength = m_overrideSnapshotSmudgeLength;
    const bool airbrush = m_overrideSnapshotAirbrush;
    const int eraserFlag = m_overrideSnapshotEraserFlag;
    const ToolMode toolMode = m_overrideSnapshotToolMode;
    const QString compositeOp = m_overrideSnapshotCompositeOp;
    m_overrideSnapshotPreset.clear();
    m_overrideSnapshotIndex = -1;
    m_overrideSnapshotPresetPath.clear();

    m_brushPreset = preset;
    m_brushPresetIndex = index;
    m_currentPresetPath = presetPath;
    m_brushSize = size;
    m_brushOpacity = opacity;
    m_brushFlow = flow;
    m_brushSpacing = spacing;
    m_brushAngle = angle;
    m_brushRotation = rotation;
    m_smudgeRate = smudgeRate;
    m_smudgeLength = smudgeLength;
    m_airbrushEnabled = airbrush;
    m_presetIsEraserOverride = eraserFlag;
    m_toolMode = toolMode;
    // 与 loadBrushPreset 的加载后处理保持一致: eraser 模式按工具态重写
    if (m_brushPreset && m_brushPreset->settings()) {
        KisPaintOpSettingsSP s = m_brushPreset->settings();
        s->setEraserMode(m_toolMode == ToolEraser);
        // 临时橡皮与当前画笔是同一个预设对象时（没有换预设），begin 之后 Kotlin 侧写入的
        // 橡皮 大小/不透明度/流量/混合模式 落在这个对象的 settings 上；上面只还原了成员，
        // 这里把 settings 也还原到快照值，否则下一笔读到的是橡皮的值
        s->setPaintOpSize(size);
        s->setPaintOpOpacity(opacity);
        s->setPaintOpFlow(flow);
        if (!compositeOp.isEmpty()) {
            s->setPaintOpCompositeOp(compositeOp);
        }
    }
    // 压感曲线缓存按 owner 指针失效 (覆盖期间可能已指向橡皮 settings)
    {
        QMutexLocker locker(&m_sizeCurveMutex);
        m_sizeCurveOwner = nullptr;
    }
    RPC_LOG("RPC overrideSnapshot end restore idx=%d", index);
    return true;
}

// 一次 .kpp 解析同时提取 defaults / paintOpId / tipFilename 三个只读值。
// 选择预设时 Kotlin 会在主线程连查这三个值 (原本各做一次完整解析:
// 读文件 + PNG zTXt 解压 + 正则扫描 + XML loadFromDevice), 是"换笔卡顿"
// 的主因; 这里解析一次后按 mtime+size 缓存 (保存预设改写文件即失效)。
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
    KisPaintOpPresetSP preset;
    if (path.endsWith(QLatin1String(".myb"), Qt::CaseInsensitive)) {
        preset = KisPaintOpPresetSP(krita_create_mypaint_preset(path));
    } else {
        preset = KisPaintOpPresetSP(new KisPaintOpPreset(m_presets[index].first));
    }
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
        s->setProperty("PressureOpacity", true);
    }
    if (!s->hasProperty("PressureFlow") && s->getBool("FlowUseCurve", false)) {
        s->setProperty("PressureFlow", true);
    }
    if (!s->hasProperty("PressureSize") && s->getBool("SizeUseCurve", false)) {
        s->setProperty("PressureSize", true);
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
    if (!(spacing > 0.0) || spacing != spacing || spacing > 10.0) {
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
    // 附加旋转偏角默认初始为 0.0, 绝不可读取 Krita 的 RotationValue(曲线强度乘数 1.0)
    const double rotation = 0.0;

    // Dynamics
    bool hasPressureSize = true;
    if (s->hasProperty("PressureSize")) {
        hasPressureSize = s->getBool("PressureSize", true);
    } else if (s->hasProperty("SizeUseCurve")) {
        hasPressureSize = s->getBool("SizeUseCurve", false);
    }
    const double pressureSize = hasPressureSize ? 1.0 : 0.0;

    bool hasPressureOpacity = false;
    if (s->hasProperty("PressureOpacity")) {
        hasPressureOpacity = s->getBool("PressureOpacity", false) && s->getBool("OpacityUseCurve", true);
    } else if (s->hasProperty("OpacityUseCurve")) {
        hasPressureOpacity = s->getBool("OpacityUseCurve", false);
    }
    const double pressureOpacity = hasPressureOpacity ? 1.0 : 0.0;

    bool hasPressureFlow = false;
    if (s->hasProperty("PressureFlow")) {
        hasPressureFlow = s->getBool("PressureFlow", false) && s->getBool("FlowUseCurve", true);
    } else if (s->hasProperty("FlowUseCurve")) {
        hasPressureFlow = s->getBool("FlowUseCurve", false);
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
    const QString &path = m_presets[index].second;
    if (path.endsWith(QLatin1String(".myb"), Qt::CaseInsensitive)) {
        QString prevPath = path;
        prevPath.chop(4);
        prevPath += QStringLiteral("_prev.png");
        QFile prevFile(prevPath);
        if (prevFile.open(QIODevice::ReadOnly)) {
            if (prevFile.size() > 0 && prevFile.size() <= 8 * 1024 * 1024) {
                return prevFile.readAll();
            }
            return QByteArray();
        }
    }
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return QByteArray();
    }
    if (f.size() <= 0 || f.size() > 8 * 1024 * 1024) {
        return QByteArray();
    }
    return f.readAll();
}

void ReverieCore::setBrushSize(qreal v)
{
    m_brushSize = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpSize(v);
        // 尺寸变化会改变湿笔间距下限 (先选预设再把尺寸拉到 400px 的情况)
        auto *bs = dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
        if (bs && isColorSmudgePreset(m_brushPreset) && m_brushSpacing > 0.0) {
            writeBrushSpacing(bs, m_brushPreset, m_brushSize, m_brushSpacing);
        }
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
    writeBrushSpacing(bs, m_brushPreset, m_brushSize, v);
    // 不再写 SpacingValue: 它是 KisSpacingOption 的 extraScale 乘数, 会与
    // brush spacing 相乘把间距再缩一次 (PressureSpacing=true 的预设尤其明显)。
}

void ReverieCore::setBrushAngle(qreal v)
{
    m_brushAngle = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        const qreal effective = std::fmod(m_brushAngle + m_brushRotation, 360.0);
        m_brushPreset->settings()->setPaintOpAngle(effective < 0.0 ? effective + 360.0 : effective);
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
        // Scattering/AxisX|AxisY belong to the preset (PresetPropertyMap SCATTER_BOTH_AXES);
        // KisScatterOptionData::read defaults both to true when the keys are absent.
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
    m_brushRotation = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        const qreal effective = std::fmod(m_brushAngle + m_brushRotation, 360.0);
        m_brushPreset->settings()->setPaintOpAngle(effective < 0.0 ? effective + 360.0 : effective);
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

    auto makeSensorXml = [&](const char *sensorPropKey) {
        QString current = s->getString(sensorPropKey);
        QString sid = QStringLiteral("pressure");
        if (!current.isEmpty()) {
            QRegularExpression re(QStringLiteral("id=\"([^\"]+)\""));
            auto m = re.match(current);
            if (m.hasMatch()) {
                sid = m.captured(1);
            }
        }
        if (sid == QStringLiteral("drawingangle")) {
            return QStringLiteral("<!DOCTYPE params><params fanCornersStep=\"30\" fanCornersEnabled=\"0\" angleOffset=\"0\" id=\"drawingangle\"><curve>%1</curve></params>").arg(curveStr);
        } else {
            return QStringLiteral("<!DOCTYPE params><params id=\"%1\"><curve>%2</curve></params>").arg(sid, curveStr);
        }
    };

    const bool useSize = (sizeStrength > 0.001);
    s->setProperty("PressureSize", useSize);
    s->setProperty("SizeUseCurve", useSize);
    s->setProperty("SizeValue", sizeStrength);
    if (useSize) {
        s->setProperty("SizeSensor", makeSensorXml("SizeSensor"));
    }

    const bool useOpacity = (opacityStrength > 0.001);
    s->setProperty("PressureOpacity", useOpacity);
    s->setProperty("OpacityUseCurve", useOpacity);
    if (useOpacity) {
        s->setProperty("OpacitySensor", makeSensorXml("OpacitySensor"));
    }

    const bool useFlow = (flowStrength > 0.001);
    s->setProperty("PressureFlow", useFlow);
    s->setProperty("FlowUseCurve", useFlow);
    if (useFlow) {
        s->setProperty("FlowSensor", makeSensorXml("FlowSensor"));
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
    const bool isMasterAttr = (opt.compare(QLatin1String("Opacity"), Qt::CaseInsensitive) == 0) ||
                             (opt.compare(QLatin1String("Flow"), Qt::CaseInsensitive) == 0) ||
                             (opt.compare(QLatin1String("Spacing"), Qt::CaseInsensitive) == 0);
    if (!isMasterAttr) {
        s->setProperty(valKey.toLatin1().constData(), strength);
    }

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
    if (enabled) {
        s->setProperty("PressureRotation", true);
        s->setProperty("RotationUseCurve", true);
        s->setProperty("RotationSensor", QStringLiteral("<!DOCTYPE params><params fanCornersStep=\"30\" fanCornersEnabled=\"0\" angleOffset=\"0\" id=\"drawingangle\"><curve>0,0;1,1;</curve></params>"));
        s->setProperty("RotationValue", 1.0);
    } else {
        const QString sensorXml = s->getString("RotationSensor");
        if (sensorXml.contains(QStringLiteral("id=\"drawingangle\""))) {
            s->setProperty("PressureRotation", false);
            s->setProperty("RotationUseCurve", false);
        }
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

/*
 * 涂抹类预设 (colorsmudge) 的 Lightness 策略要求画笔设备上挂着
 * KisColorSmudgeInterstrokeData: 真机笔画里这份数据由 KisTransaction 通过
 * interstroke factory 注入, 而画笔工作室的取样预览没有事务 —— 策略会走
 * KIS_SAFE_ASSERT_RECOVER 兜底分支, 那条分支又忘了给 layer overlay 指针赋值,
 * 第一次 readRects 就解引用空指针 SIGSEGV (1.6.2 线上 51f7f06d17)。
 *
 * 这里直接按预设自带的 factory 手工挂一份数据, 让预览与真笔画走同一条策略路径;
 * 预览结束 (或下一次 Start) 再摘掉。非涂抹预设的 factory 返回空, 调用是 no-op。
 */
void ReverieCore::ensureScratchpadInterstrokeData()
{
    if (!m_scratchpadDev || !m_brushPreset) {
        return;
    }
    KisInterstrokeDataFactory *factory =
        KisPaintOpRegistry::instance()->createInterstrokeDataFactory(m_brushPreset);
    if (!factory) {
        // 当前预设不需要 interstroke data: 摘掉旧的, 避免别的策略拿到涂抹专用设备
        detachScratchpadInterstrokeData();
        return;
    }
    // 画笔工作室切换预设不会重建预览设备, 所以每次落笔前都要对齐一次;
    // 同一类预设 (factory 认为兼容) 直接复用, 不重新分配 overlay。
    const KisInterstrokeDataSP existing = m_scratchpadDev->interstrokeData();
    if (existing && factory->isCompatible(existing.data())) {
        delete factory;
        return;
    }
    KisInterstrokeData *rawData = factory->create(m_scratchpadDev);
    delete factory;
    if (!rawData) {
        return;
    }
    KUndo2Command *cmd =
        m_scratchpadDev->createChangeInterstrokeDataCommand(KisInterstrokeDataSP(rawData));
    if (cmd) {
        cmd->redo();
        delete cmd;
    }
}

void ReverieCore::detachScratchpadInterstrokeData()
{
    if (!m_scratchpadDev) {
        return;
    }
    KUndo2Command *cmd =
        m_scratchpadDev->createChangeInterstrokeDataCommand(KisInterstrokeDataSP());
    if (cmd) {
        cmd->redo();
        delete cmd;
    }
}

bool ReverieCore::scratchpadStart(int w, int h)
{
    if (w <= 0 || h <= 0) return false;
    const KoColorSpace *cs = (m_document && m_document->colorSpace())
        ? m_document->colorSpace()
        : KoColorSpaceRegistry::instance()->rgb8();
    if (!m_scratchpadDev || m_scratchpadWidth != w || m_scratchpadHeight != h) {
        // 换设备前先摘掉引用旧设备的 interstroke data, 否则涂抹策略会拿着悬空的 overlay
        detachScratchpadInterstrokeData();
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
    // 涂抹类预设的策略要求设备上挂着 interstroke data (真笔画由事务注入, 预览没有事务)
    ensureScratchpadInterstrokeData();
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
    m_scratchpadPainter->setRunnableStrokeJobsInterface(&m_fakeExecutor);

    KisNodeSP node = new KisPaintLayer(m_document, QStringLiteral("ScratchPad"), OPACITY_OPAQUE_U8, m_scratchpadDev);
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
    if (m_presetIsEraserOverride == 1) {
        compOp = QStringLiteral("erase");
    }
    m_scratchpadPainter->setCompositeOpId(compOp.isEmpty() ? QStringLiteral("normal") : compOp);

    const QPointF pt(x, y);
    m_scratchpadDistInfo = new KisDistanceInformation(pt, 0.0);
    m_scratchpadTimer.start();
    KisPaintInformation pi(pt, pressure, tiltX, tiltY, rotation, 0.0, 1.0, 0.0, 0.0);
    m_scratchpadPainter->paintAt(pi, m_scratchpadDistInfo);

    if (KisPaintOp *op = m_scratchpadPainter->paintOp()) {
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = op->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
    }

    m_scratchpadLastSample = StrokeSample{pt, pressure, tiltX, tiltY, rotation, 0.0};
    m_scratchpadStrokeActive = true;
    return true;
}

bool ReverieCore::scratchpadStrokeMove(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation)
{
    if (!m_scratchpadStrokeActive || !m_scratchpadPainter || !m_scratchpadDistInfo) return false;
    const QPointF pt(x, y);
    const qreal curTime = m_scratchpadTimer.nsecsElapsed() / 1e9;
    const qreal dist = QLineF(m_scratchpadLastSample.imgPos, pt).length();
    const qreal speed = reverie::StrokeTimeContract::speedPxPerMs(dist, m_scratchpadLastSample.time, curTime);
    const qreal t0Ms = reverie::StrokeTimeContract::toKritaMs(m_scratchpadLastSample.time);
    const qreal t1Ms = reverie::StrokeTimeContract::toKritaMs(curTime);

    KisPaintInformation pi1(m_scratchpadLastSample.imgPos, m_scratchpadLastSample.pressure,
                            m_scratchpadLastSample.tiltX, m_scratchpadLastSample.tiltY,
                            m_scratchpadLastSample.rotation, 0.0, 1.0, t0Ms, speed);
    KisPaintInformation pi2(pt, pressure, tiltX, tiltY, rotation, 0.0, 1.0, t1Ms, speed);
    m_scratchpadPainter->paintLine(pi1, pi2, m_scratchpadDistInfo);

    if (KisPaintOp *op = m_scratchpadPainter->paintOp()) {
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = op->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
    }

    m_scratchpadLastSample = StrokeSample{pt, pressure, tiltX, tiltY, rotation, curTime};
    return true;
}

void ReverieCore::scratchpadStrokeEnd()
{
    if (m_scratchpadPainter) {
        if (KisPaintOp *op = m_scratchpadPainter->paintOp()) {
            while (true) {
                QVector<KisRunnableStrokeJobData *> jobs;
                auto result = op->doAsynchronousUpdate(jobs);
                for (auto *j : jobs) {
                    j->run();
                    delete j;
                }
                if (jobs.isEmpty() || !result.second) {
                    break;
                }
            }
        }
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

QVector<float> ReverieCore::currentBrushOutline()
{
    if (!m_brushPreset || !m_brushPreset->settings()) {
        return {};
    }
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
    if (!bs) {
        return {};
    }
    KisBrushSP brush = bs->brush();
    if (!brush || !brush->valid() || brush->width() <= 0 || brush->height() <= 0) {
        return {};
    }
    if (KisImagePipeBrush *pipeBrush = dynamic_cast<KisImagePipeBrush *>(brush.data())) {
        if (pipeBrush->brushes().isEmpty()) {
            return {};
        }
        KisGbrBrushSP first = pipeBrush->brushes().first();
        if (!first || !first->valid()) {
            return {};
        }
    }

    KisOptimizedBrushOutline outline = brush->outline();
    if (outline.isEmpty()) {
        return {};
    }

    const QRectF bounds = outline.boundingRect();
    if (bounds.width() <= 0.0 || bounds.height() <= 0.0) {
        return {};
    }

    const qreal cx = bounds.center().x();
    const qreal cy = bounds.center().y();
    // 归一化基准半径: 取半宽与半高的较大者，使多边形点落在 [-1.0, 1.0] 范围内
    const qreal baseR = qMax(bounds.width(), bounds.height()) * 0.5;
    if (baseR <= 0.0) {
        return {};
    }

    QVector<QPolygonF> polys;
    for (auto it = outline.begin(); it != outline.end(); ++it) {
        const QPolygonF p = *it;
        if (p.size() >= 3) {
            polys.append(p);
        }
    }
    if (polys.isEmpty()) {
        return {};
    }

    int totalPts = 0;
    for (const QPolygonF &p : polys) {
        totalPts += p.size();
    }

    QVector<float> out;
    out.reserve(1 + polys.size() + totalPts * 2);
    out.append(static_cast<float>(polys.size()));
    for (const QPolygonF &poly : polys) {
        out.append(static_cast<float>(poly.size()));
        for (const QPointF &pt : poly) {
            out.append(static_cast<float>((pt.x() - cx) / baseR));
            out.append(static_cast<float>((pt.y() - cy) / baseR));
        }
    }
    return out;
}



