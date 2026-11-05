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
#include <QDomDocument>
#include <QUrl>
#include <algorithm>
#include <cmath>
#include <QtEndian>
#include <zlib.h>
#include <QCryptographicHash>

#include <kis_abr_brush_collection.h>
#include <kis_abr_brush.h>
#include <KoEmbeddedResource.h>
#include <KisResourceTypes.h>
// C3: 试画板管线对齐主画布 (KPI 9 参/随机源/蒙版链路/间接暂存)
#include "StrokePaintInfo.h"
#include <strokes/KisMaskingBrushRenderer.h>
#include <strokes/KisMaskedFreehandStrokePainter.h>
#include <KisFreehandStrokeInfo.h>
#include <QRandomGenerator>
// calcAutoSpacing(): 自动间距 (useAutoSpacing="1") 的生效值计算, 与
// KisPaintOpUtils::effectiveSpacing 内部用的是同一个公式。
#include <kis_paintop_utils.h>
#include "BrushDefinitionGuard.h"
#include "BrushTipHealth.h"
#include "MemoryBrushKind.h"

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

namespace {

// XML 里提供的 MD5 解析为上游匹配语义的 hex 形态:
// KoResource::setMD5Sum 要求 hex 编码字符串 ("It must be in hex-encoded string format");
// kpp 的 Texture/Pattern/PatternMD5Sum 直接是 hex, Texture/Pattern/PatternMD5 存的是
// md5Base64 (原始 16 字节摘要的 base64), 老预设 (如 deevad 系) 还存在 base64(base64)
// 双层形态。按 32 位 hex → 16 字节摘要逐层剥判, 解不出返回空 (由调用方对原始字节现算)。
QString resolveMd5HexFromXml(const QString &xmlMd5)
{
    const QString cur = xmlMd5.trimmed();
    if (cur.isEmpty()) return QString();
    // 32 位 hex 直接原样透传 (保留原大小写: reload 时 exactMatch 按原样字符串比较)
    static const QRegularExpression hexRe(QStringLiteral("^[0-9a-fA-F]{32}$"));
    if (hexRe.match(cur).hasMatch()) return cur;
    // base64 剥层严格校验: fromBase64 默认忽略非法字符, 32 字符垃圾可能误剥出伪 md5,
    // 带 AbortOnDecodingError 剥不出立即放弃 (返空由调用方对解码字节现算)
    auto strictB64 = [](const QByteArray &in) -> QByteArray {
        QByteArray::FromBase64Result r =
            QByteArray::fromBase64Encoding(in, QByteArray::AbortOnBase64DecodingErrors);
        return r ? r.decoded : QByteArray();
    };
    QByteArray decoded = strictB64(cur.toLatin1());
    for (int i = 0; i < 3 && !decoded.isEmpty(); ++i) {
        if (decoded.size() == 16) {
            return QString::fromLatin1(decoded.toHex());
        }
        const QByteArray next = strictB64(decoded);
        if (next.isEmpty() || next == decoded) break;
        decoded = next;
    }
    return QString();
}

// ===================== R5-A: kpp <Brush> 定义属性注入 =====================
// 背景: kpp <Brush> 元素的 spacing/useAutoSpacing/autoSpacingCoeff 与色彩调整属性
// 在运行时从未进入绑定笔刷对象, inkP 系预设的 dab 步长因此退化为笔尖文件内嵌
// 的线性间距 (真机日志 brushRESOLVED spacing=0.100), 大尺寸下表现成周期黑条。
//
// 上游语义链路 (已逐行核实, krita-reverie @18671c5):
//   - kis_brush_option.cpp:50-55  BrushReader 读 brush_definition → KisBrush::fromXML
//   - kis_brush.cpp:453-467       fromXML → KisBrushRegistry::createBrush → 工厂分发
//   - kis_predefined_brush_factory.cpp:101-190  解析 <Brush> 属性 (spacing/自动间距/
//     KisColorfulBrush 四项色彩调整/brushApplication 四分支), :36-47 先 clone 再应用
//     ("we always return a copy of the brush!")
//   - kis_brush_based_paintop.cpp:181-190/:200-212  paintop 直通 brush->spacing()/
//     autoSpacingActive()/autoSpacingCoeff() 进 effectiveSpacing
// 本组辅助在绑定完成后把元素属性再施加一次 (幂等): 覆盖工厂属性被任何路径丢掉的
// 情形, 并让 brushRESOLVED/spacingProbe 的可观测值与 kpp 定义一致。

// 解析 brush_definition 属性串 (param CDATA 文本) → <Brush> 元素
QDomElement parseBrushElementXml(const QString &xml)
{
    if (xml.isEmpty()) return QDomElement();
    QDomDocument d;
    if (!d.setContent(xml)) return QDomElement();
    return d.firstChildElement(QStringLiteral("Brush"));
}

// 与上游 KoResourceSource::bestMatch (KisResourcesInterface.cpp:73-160) 对齐的签名
// 匹配, 匹配规则: md5sum 优先 (大小写不敏感), 否则 filename 全等或文件名部分等。
// 双方均提供 md5 时只按 md5 判定 (md5 优先语义)。
bool brushElementMatchesSignature(const QDomElement &e, const KisBrush *b)
{
    if (e.isNull() || !b) return false;
    const QString eMd5 = e.attribute(QStringLiteral("md5sum")).trimmed();
    const QString bMd5 = b->md5Sum();
    if (!eMd5.isEmpty() && !bMd5.isEmpty()) {
        return eMd5.compare(bMd5, Qt::CaseInsensitive) == 0;
    }
    const QString eFile = e.attribute(QStringLiteral("filename")).trimmed();
    const QString bFile = b->filename();
    if (eFile.isEmpty() || bFile.isEmpty()) return false;
    if (eFile == bFile) return true;
    return QFileInfo(eFile).fileName() == QFileInfo(bFile).fileName();
}

// 在会话资源池 (m_loadedBrushes + KisLocalStrokeResources) 里按元素签名解析笔刷,
// md5 采用大小写不敏感比较 (比上游 bestMatch 多救一类签名大小写不一致的预设)。
KisBrushSP resolvePooledBrushByElement(const QDomElement &e,
                                       const KisResourcesInterfaceSP &resources,
                                       const QHash<QString, KisBrushSP> &loadedBrushes)
{
    if (e.isNull()) return KisBrushSP();
    const QString eMd5 = e.attribute(QStringLiteral("md5sum")).trimmed();
    const QString eFile = e.attribute(QStringLiteral("filename")).trimmed();
    if (eMd5.isEmpty() && eFile.isEmpty()) return KisBrushSP();

    auto scanHash = [&eMd5, &eFile](const QHash<QString, KisBrushSP> &pool) -> KisBrushSP {
        for (auto it = pool.cbegin(); it != pool.cend(); ++it) {
            const KisBrushSP b = it.value();
            if (!b) continue;
            if (!eMd5.isEmpty() && !b->md5Sum().isEmpty() &&
                    b->md5Sum().compare(eMd5, Qt::CaseInsensitive) == 0) {
                return b;
            }
            if (!eFile.isEmpty() && !b->filename().isEmpty() &&
                    (b->filename() == eFile ||
                     QFileInfo(b->filename()).fileName() == QFileInfo(eFile).fileName())) {
                return b;
            }
        }
        return KisBrushSP();
    };

    KisBrushSP hit = scanHash(loadedBrushes);
    if (hit) return hit;

    KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(resources.data());
    if (!lr) return KisBrushSP();
    const QList<KoResourceSP> all = lr->resources();
    for (const KoResourceSP &r : all) {
        const KisBrushSP b = r.dynamicCast<KisBrush>();
        if (!b) continue;
        if (!eMd5.isEmpty() && !b->md5Sum().isEmpty() &&
                b->md5Sum().compare(eMd5, Qt::CaseInsensitive) == 0) {
            return b;
        }
        if (!eFile.isEmpty() && !b->filename().isEmpty() &&
                (b->filename() == eFile ||
                 QFileInfo(b->filename()).fileName() == QFileInfo(eFile).fileName())) {
            return b;
        }
    }
    return KisBrushSP();
}

// 逐字段对齐上游 kis_predefined_brush_factory.cpp:101-190 的 <Brush> 属性语义。
// 注意: scale/angle 禁碰 —— 它们由 setPaintOpSize/setPaintOpAngle 通道承载
// (kis_brush_based_paintop_settings.cpp:167-189: setPaintOpSize → setUserEffectiveSize,
// KisScalingSizeBrush::setUserEffectiveSize → setScale; setPaintOpAngle → setAngle),
// 尺寸/角度观感已对齐 Krita, 在笔刷对象上重复应用会双重缩放/旋转。
void applyBrushDefinitionAttributes(KisBrush *brush, const QDomElement &e)
{
    if (!brush || e.isNull()) return;
    if (e.tagName() != QLatin1String("Brush")) return;
    // auto_brush 走 kis_auto_brush_factory 的 MaskGenerator/randomness/density 字段族,
    // 属另一工厂, 本注入不碰
    if (e.attribute(QStringLiteral("type")) == QLatin1String("auto_brush")) return;

    // spacing: 仅当元素带该属性时透传 (上游 predefined 缺省 0.25, 这里不写缺省值)
    if (e.hasAttribute(QStringLiteral("spacing"))) {
        brush->setSpacing(e.attribute(QStringLiteral("spacing")).toDouble());
    }
    // 自动间距: 仅当元素含任一属性时调用, 缺失侧按上游缺省 (0 / 1.0) 语义
    if (e.hasAttribute(QStringLiteral("useAutoSpacing")) ||
            e.hasAttribute(QStringLiteral("autoSpacingCoeff"))) {
        const bool useAuto = e.attribute(QStringLiteral("useAutoSpacing"), QStringLiteral("0")).toInt() != 0;
        const qreal coeff = e.attribute(QStringLiteral("autoSpacingCoeff"), QStringLiteral("1.0")).toDouble();
        brush->setAutoSpacing(useAuto, coeff);
    }

    // KisColorfulBrush 四项色彩调整 (含 Krita 4.x AdjustmentVersion<2 旧版翻倍换算,
    // 上游 :113-157 同款)
    KisColorfulBrush *colorful = dynamic_cast<KisColorfulBrush *>(brush);
    if (colorful) {
        quint8 midPoint = static_cast<quint8>(
            e.attribute(QStringLiteral("AdjustmentMidPoint"), QStringLiteral("127")).toInt());
        qreal brightness = e.attribute(QStringLiteral("BrightnessAdjustment")).toDouble();
        qreal contrast = e.attribute(QStringLiteral("ContrastAdjustment")).toDouble();
        const int adjustmentVersion =
            e.attribute(QStringLiteral("AdjustmentVersion"), QStringLiteral("1")).toInt();
        const bool autoAdjustMidPoint =
            e.attribute(QStringLiteral("AutoAdjustMidPoint"), QStringLiteral("0")).toInt() != 0;
        const bool hasAutoAdjustMidPoint = e.hasAttribute(QStringLiteral("AutoAdjustMidPoint"));

        if (adjustmentVersion < 2 && !hasAutoAdjustMidPoint) {
            // Krita 4.x 把亮度调整应用了两次, 5.x 打开时做钝参数换算
            midPoint = static_cast<quint8>(qBound(0, 127 + (static_cast<int>(midPoint) - 127) * 2, 255));
            brightness *= 2.0;
            contrast *= 2.0;
            // Krita 5 改了负对比度公式
            if (contrast < 0.0) {
                contrast = 1.0 / (1.0 - contrast) - 1.0;
            }
        }

        colorful->setAdjustmentMidPoint(midPoint);
        colorful->setBrightnessAdjustment(brightness);
        colorful->setContrastAdjustment(contrast);
        colorful->setAutoAdjustMidPoint(autoAdjustMidPoint);
    }

    // brushApplication 四分支 (上游 :159-190), legacy 语义同款 lambda
    auto legacyBrushApplication = [](KisColorfulBrush *cb, bool forceColorToAlpha) -> enumBrushApplication {
        return (cb && cb->hasColorAndTransparency() && !forceColorToAlpha) ? IMAGESTAMP : ALPHAMASK;
    };
    enumBrushApplication app = ALPHAMASK;
    if (e.hasAttribute(QStringLiteral("preserveLightness"))) {
        const bool preserveLightness =
            e.attribute(QStringLiteral("preserveLightness"), QStringLiteral("0")).toInt() != 0;
        const bool useColorAsMask = e.attribute(QStringLiteral("ColorAsMask"), QStringLiteral("1")).toInt() != 0;
        app = preserveLightness ? LIGHTNESSMAP : legacyBrushApplication(colorful, useColorAsMask);
    } else if (e.hasAttribute(QStringLiteral("brushApplication"))) {
        app = static_cast<enumBrushApplication>(
            e.attribute(QStringLiteral("brushApplication"), QStringLiteral("0")).toInt());
    } else if (e.hasAttribute(QStringLiteral("ColorAsMask"))) {
        const bool useColorAsMask = e.attribute(QStringLiteral("ColorAsMask"), QStringLiteral("1")).toInt() != 0;
        app = legacyBrushApplication(colorful, useColorAsMask);
    } else {
        // 无属性: 4.4 之前的自动启发 (有色彩 ⇒ IMAGESTAMP)
        app = legacyBrushApplication(colorful, false);
    }
    brush->setBrushApplication(app);
}

// 判定绑定实例是否指向跨预设共享的池内笔刷 (m_loadedBrushes / lr 资源表)。
// 按上游绑定链路, settings->brush() 恒为工厂 clone (预设私有), 该分支是防御性的;
// 一旦触发, 用 KisBrush::clone() 出预设私有副本再注入, 严禁就地改池内共享实例。
bool isPooledBrushInstance(const KisBrushSP &bound,
                           const KisResourcesInterfaceSP &resources,
                           const QHash<QString, KisBrushSP> &loadedBrushes)
{
    if (!bound) return false;
    for (auto it = loadedBrushes.cbegin(); it != loadedBrushes.cend(); ++it) {
        if (it.value() == bound) return true;
    }
    KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(resources.data());
    if (!lr) return false;
    const QList<KoResourceSP> all = lr->resources();
    for (const KoResourceSP &r : all) {
        if (r == bound.staticCast<KoResource>()) return true;
    }
    return false;
}

// 把注入后的预设私有笔刷回写为 brush_definition (供 BrushWriter/重解析链路消费),
// 并补回 toXML 序列化不承载的属性: scale/angle (尺寸/角度通道消费), BrushVersion
// (=1 时解析期 scale 翻倍), preserveLightness (LIGHTNESSMAP 判定的 legacy 键)。
void rebindBrushDefinitionProperty(KisBrushBasedPaintOpSettings *bs,
                                   const KisBrushSP &priv,
                                   const QDomElement &e)
{
    if (!bs || !priv) return;
    KisBrushOptionProperties opt;
    opt.setBrush(priv);
    opt.writeOptionSetting(bs);

    const QString cur = bs->getString(QStringLiteral("brush_definition"));
    QDomDocument d;
    if (cur.isEmpty() || !d.setContent(cur)) return;
    QDomElement el = d.firstChildElement(QStringLiteral("Brush"));
    if (el.isNull() || e.isNull()) return;
    static const char *kCarriedAttrs[] = {
        "scale", "angle", "BrushVersion", "preserveLightness",
        "spacing", "useAutoSpacing", "autoSpacingCoeff",
        "AdjustmentMidPoint", "BrightnessAdjustment", "ContrastAdjustment",
        "AdjustmentVersion", "AutoAdjustMidPoint", "brushApplication"
    };
    for (const char *key : kCarriedAttrs) {
        if (e.hasAttribute(QLatin1String(key))) {
            el.setAttribute(QLatin1String(key), e.attribute(QLatin1String(key)));
        }
    }
    bs->setProperty(QStringLiteral("brush_definition"), d.toString());
    KisBrushSP rebound = bs->brush();
    if (rebound) {
        applyBrushDefinitionAttributes(rebound.data(), e);
    }
}
// =================== R5-A 注入辅助结束 ===================

// 内嵌图案的内存注册: 字节 → KoPattern (QImage 路径优先, GPAT/.pat 走 QBuffer 退路),
// MD5 按上游 hex 语义设置 (md5Hex 为空时对原始字节现算), 失败返回空指针。
KoPatternSP registerMemoryPattern(const QByteArray &bytes, const QString &name,
                                  const QString &fileName, const QString &md5Hex,
                                  const KisResourcesInterfaceSP &resourcesInterface)
{
    if (bytes.isEmpty()) return KoPatternSP();

    const QString effName = !name.isEmpty() ? name : fileName;
    const QString effFile = !fileName.isEmpty() ? fileName : name;
    KoPatternSP pat;
    QImage img = QImage::fromData(bytes);
    if (!img.isNull()) {
        pat = KoPatternSP(new KoPattern(img, effName, effFile));
    } else {
        pat = KoPatternSP(new KoPattern(effFile));
        QByteArray bufBytes = bytes;
        QBuffer buf(&bufBytes);
        buf.open(QIODevice::ReadOnly);
        const bool ok = pat->loadFromDevice(&buf, resourcesInterface);
        buf.close();
        if (!ok) return KoPatternSP();
    }
    if (pat->pattern().isNull()) return KoPatternSP();

    const QString effectiveMd5 = !md5Hex.isEmpty()
        ? md5Hex
        : QString::fromLatin1(QCryptographicHash::hash(bytes, QCryptographicHash::Md5).toHex());
    pat->setMD5Sum(effectiveMd5);
    if (!effFile.isEmpty()) pat->setFilename(effFile);
    if (!effName.isEmpty()) pat->setName(effName);
    RPC_LOG("RPC registerMemoryPattern name=%s file=%s md5=%s bytes=%d",
            effName.toUtf8().constData(), effFile.toUtf8().constData(),
            effectiveMd5.toUtf8().constData(), static_cast<int>(bytes.size()));
    return pat;
}

// 内嵌笔尖 (png/gbr/gih/svg) 的内存注册: 字节 → 对应 KisBrush 子类经 QBuffer 加载,
// 设置文件名/MD5 并检查 valid(), 失败返回空指针。
KisBrushSP registerMemoryBrush(const QByteArray &bytes, const QString &fileName,
                               const QString &md5Hex, bool isGbr,
                               const KisResourcesInterfaceSP &resourcesInterface)
{
    if (bytes.isEmpty() || fileName.isEmpty()) return KisBrushSP();

    const reverie::MemoryBrushKind kind = reverie::memoryBrushKindFor(fileName.toStdString(), isGbr);
    KisBrushSP brush;
    const char *kindName = "png";
    switch (kind) {
    case reverie::MemoryBrushKind::Gbr: brush = KisBrushSP(new KisGbrBrush(fileName)); kindName = "gbr"; break;
    case reverie::MemoryBrushKind::Gih: brush = KisBrushSP(new KisImagePipeBrush(fileName)); kindName = "gih"; break;
    case reverie::MemoryBrushKind::Svg: brush = KisBrushSP(new KisSvgBrush(fileName)); kindName = "svg"; break;
    case reverie::MemoryBrushKind::Png:
    default: brush = KisBrushSP(new KisPngBrush(fileName)); break;
    }
    QByteArray bufBytes = bytes;
    QBuffer buf(&bufBytes);
    buf.open(QIODevice::ReadOnly);
    const bool ok = brush->loadFromDevice(&buf, resourcesInterface);
    buf.close();
    if (!ok || !brush->valid()) return KisBrushSP();

    const QString effectiveMd5 = !md5Hex.isEmpty()
        ? md5Hex
        : QString::fromLatin1(QCryptographicHash::hash(bytes, QCryptographicHash::Md5).toHex());
    brush->setFilename(fileName);
    brush->setMD5Sum(effectiveMd5);
    RPC_LOG("RPC registerMemoryBrush file=%s md5=%s type=%s bytes=%d",
            fileName.toUtf8().constData(), effectiveMd5.toUtf8().constData(),
            kindName, static_cast<int>(bytes.size()));
    return brush;
}

// 内存注册成功后同步名字缓存: 后续 loadSingleBrushResource 对同名请求直接命中返回,
// 既防双注册也防重复的磁盘查找。
void rememberLoadedPatternNames(const QStringList &names, QSet<QString> &loadedNames)
{
    for (const QString &n : names) {
        if (!n.isEmpty()) loadedNames.insert(n);
    }
}

void rememberLoadedBrushNames(const QStringList &names, const KisBrushSP &brush,
                              QHash<QString, KisBrushSP> &loadedBrushes)
{
    for (const QString &n : names) {
        if (!n.isEmpty()) loadedBrushes.insert(n, brush);
    }
}

// 内嵌资源注册点的前置缓存查询 (不经 loadSingleBrushResource 的 isBrush 判定):
// .png/.svg 命名的内嵌图案会被其 isBrush 扩展名分支绕过 m_loadedResourceNames 命中,
// 导致每次预设加载都重复内存构造并 addResource (上游 addResource 纯 append,
// m_brushResources 会话级持有解码图像, 无上界增长)。
bool memoryResourceAlreadyRegistered(const QStringList &names,
                                     const QHash<QString, KisBrushSP> &loadedBrushes,
                                     const QSet<QString> &loadedNames)
{
    for (const QString &n : names) {
        if (n.isEmpty()) continue;
        if (loadedBrushes.contains(n) || loadedNames.contains(n)) return true;
    }
    return false;
}

} // namespace

// 对预设 settings 当前绑定的笔刷实例应用 kpp <Brush> 定义属性。
// 调用时机: 预设 loadFromDevice 之后、任何 spacing/尺寸写入与 brush() 语义消费之前。
// 覆盖全部命中路径: registerMemoryBrush 新建 / 缓存命中 / 磁盘命中最终都汇聚到
// brush_definition → bs->brush() 这一个绑定出口, 在此统一注入即全覆盖。
// 非 static: 供 ReverieCoreInternal.h 跨文件声明, 使 ReverieCoreStroke.cpp 在
// createMaskingPreset() 创建遮罩预设时也能统一注入副笔刷属性。
void injectBrushDefinitionProperties(KisBrushBasedPaintOpSettings *bs,
                                     const KisResourcesInterfaceSP &resources,
                                     const QHash<QString, KisBrushSP> &loadedBrushes)
{
    if (!bs) return;
    const QString mainDef = bs->getString(QStringLiteral("brush_definition"));
    if (mainDef.isEmpty()) return;
    const QDomElement eMain = parseBrushElementXml(mainDef);
    if (eMain.isNull()) return;
    // 主定义是 auto_brush 时笔刷对象属性由 kis_auto_brush_factory 全权负责
    if (eMain.attribute(QStringLiteral("type")) == QLatin1String("auto_brush")) return;

    const QDomElement eMask = parseBrushElementXml(
        bs->getString(QStringLiteral("MaskingBrush/Preset/brush_definition")));

    KisBrushSP bound = bs->brush();
    // 绑定落空 (bestMatch 双双不中 → fromXML 回退默认 auto_brush, 其 filename 为空):
    // 属性本就写在元素上, 缺的只是资源匹配。按本任务的匹配规则 (md5 大小写不敏感
    // 优先, 否则文件名全等/部分等) 在池里救援, 命中则把签名属性改指池内实例。
    const bool boundMissing = !bound || bound->filename().isEmpty();
    if (boundMissing) {
        const KisBrushSP hit = resolvePooledBrushByElement(eMain, resources, loadedBrushes);
        if (!hit) return; // 池里也没有: 保持现状 (md5 与文件名都对不上的边界)
        QDomDocument d;
        if (!d.setContent(mainDef)) return;
        QDomElement el = d.firstChildElement(QStringLiteral("Brush"));
        if (el.isNull()) return;
        if (!hit->md5Sum().isEmpty()) {
            el.setAttribute(QStringLiteral("md5sum"), hit->md5Sum());
        }
        if (!hit->filename().isEmpty()) {
            el.setAttribute(QStringLiteral("filename"), hit->filename());
        }
        bs->setProperty(QStringLiteral("brush_definition"), d.toString());
        RPC_LOG("RPC brushINJECT rescue file=%s md5=%s",
                hit->filename().toUtf8().constData(),
                hit->md5Sum().toUtf8().constData());
        return;
    }

    const QDomElement *target = nullptr;
    if (brushElementMatchesSignature(eMain, bound.data())) {
        target = &eMain;
    } else if (!eMask.isNull() && brushElementMatchesSignature(eMask, bound.data())) {
        target = &eMask;
    }
    if (!target) {
        RPC_LOG("RPC brushINJECT nomatch file=%s", bound->filename().toUtf8().constData());
        return;
    }

    if (isPooledBrushInstance(bound, resources, loadedBrushes)) {
        // 共享实例污染防护: 克隆出预设私有副本注入并回写 brush_definition。
        // 注意 m_savedBrush 已缓存旧实例, 本分支按上游绑定链路不可达 (工厂恒 clone),
        // 属防御路径; 真触发时下一次 BrushWriter 读写会解析出新 brush_definition。
        KisBrushSP priv = bound->clone().dynamicCast<KisBrush>();
        if (!priv) return;
        applyBrushDefinitionAttributes(priv.data(), *target);
        rebindBrushDefinitionProperty(bs, priv, *target);
        RPC_LOG("RPC brushINJECT pooledClone file=%s spacing=%.3f auto=%d coeff=%.3f",
                priv->filename().toUtf8().constData(),
                (double)priv->spacing(),
                priv->autoSpacingActive() ? 1 : 0,
                (double)priv->autoSpacingCoeff());
        return;
    }

    // 常规路径: 绑定实例即工厂 clone (预设私有), 同步写回 XML 确保后续从 XML 重建时不丢失属性
    applyBrushDefinitionAttributes(bound.data(), *target);
    rebindBrushDefinitionProperty(bs, bound, *target);
    RPC_LOG("RPC brushINJECT bound file=%s spacing=%.3f auto=%d coeff=%.3f",
            bound->filename().toUtf8().constData(),
            (double)bound->spacing(),
            bound->autoSpacingActive() ? 1 : 0,
            (double)bound->autoSpacingCoeff());
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
    // 不再以 m_brushDir 为前置条件: 内嵌资源走内存注册时磁盘目录可以为空
    if (kppPath.isEmpty()) return;
    QFile f(kppPath);
    if (!f.open(QIODevice::ReadOnly)) return;
    const QByteArray data = f.readAll();
    f.close();

    // 内嵌资源注册的注入点: 与 loadSingleBrushResource/sideLoadedResources 共用同一本地资源池
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());

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
                        const QString resType = getAttr(QStringLiteral("type"));
                        const QString resName = getAttr(QStringLiteral("name"));
                        const QString resMd5 = resolveMd5HexFromXml(getAttr(QStringLiteral("md5sum")));

                        // S1 前置按名查缓存 (绕开 loadSingleBrushResource 的 isBrush 误判), 命中即已注册
                        if (memoryResourceAlreadyRegistered({fn, resName},
                                                            m_loadedBrushes, m_loadedResourceNames)) {
                            continue;
                        }

                        // 磁盘优先: 磁盘上有或缓存里已注册过则直接命中
                        if (!fn.isEmpty() && loadSingleBrushResource(fn)) {
                            continue;
                        }

                        // 磁盘缺失 → 内存构造补位 (上游语义: <resource> 文本为资源原始字节的 base64)
                        const QByteArray resBytes = decodeBase64Safe(b64);
                        if (resBytes.isEmpty() || !lr) continue;

                        const QString sigFile = QFileInfo(fn).fileName();
                        const bool isGbr = sigFile.endsWith(QLatin1String(".gbr"), Qt::CaseInsensitive);
                        const bool isPat = resType == ResourceType::Patterns ||
                                           sigFile.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive);
                        if (isPat) {
                            KoPatternSP pat = registerMemoryPattern(resBytes, resName,
                                                                    !sigFile.isEmpty() ? sigFile : resName,
                                                                    resMd5, m_brushResources);
                            if (pat) {
                                lr->addResource(KoResourceSP(pat));
                                rememberLoadedPatternNames({resName, sigFile,
                                                            QFileInfo(sigFile).completeBaseName()},
                                                           m_loadedResourceNames);
                            }
                        } else if (resType == ResourceType::Brushes ||
                                   isGbr ||
                                   sigFile.endsWith(QLatin1String(".png"), Qt::CaseInsensitive)) {
                            // G1: 无 filename 属性时按 name 回退 (与 pattern 分支同款)
                            const QString brushFile = !sigFile.isEmpty() ? sigFile : resName;
                            KisBrushSP b = registerMemoryBrush(resBytes, brushFile, resMd5, isGbr,
                                                               m_brushResources);
                            if (b) {
                                lr->addResource(b.staticCast<KoResource>());
                                // 原始 filename 带子目录时 (如 "brush tips for DA/xxx.png")
                                // 追加别名克隆 (仅换 filename, md5/name 不变), 使 kpp 里
                                // 带子目录引用在无 md5 精确匹配时也能命中内存注册的笔尖
                                if (!fn.isEmpty() && fn != brushFile) {
                                    KisBrushSP aliasSp = b->clone().dynamicCast<KisBrush>();
                                    if (aliasSp) {
                                        aliasSp->setFilename(fn);
                                        lr->addResource(aliasSp.staticCast<KoResource>());
                                    }
                                }
                                rememberLoadedBrushNames({fn, brushFile, QFileInfo(brushFile).completeBaseName(),
                                                          resName},
                                                         b, m_loadedBrushes);
                                if (!fn.isEmpty()) m_loadedResourceNames.insert(fn);
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

                            // S1 前置按名查缓存 (绕开 loadSingleBrushResource 的 isBrush 误判):
                            // 已注册则磁盘与内存两路都跳过, 杜绝同名字内嵌图案重复追加
                            if (!memoryResourceAlreadyRegistered({patName, patFileName},
                                                                 m_loadedBrushes, m_loadedResourceNames)) {
                                // 磁盘优先 (两个文件名都保持原有的加载尝试, 不短路)
                                bool diskOk = loadSingleBrushResource(patName);
                                if (!patFileName.isEmpty() && patFileName != patName) {
                                    diskOk = loadSingleBrushResource(patFileName) || diskOk;
                                }

                                // 磁盘缺失 → 内存构造补位; MD5 优先取 XML (PatternMD5Sum hex / PatternMD5 md5Base64)
                                if (!diskOk && lr) {
                                    static const QRegularExpression patMd5SumRe(
                                        QStringLiteral("<param\\b[^>]*?name=[\"']Texture/Pattern/PatternMD5Sum[\"'][^>]*>(?:<!\\[CDATA\\[)?([A-Za-z0-9+/=\\r\\n]+)(?:\\]\\]>)?</param>"),
                                        QRegularExpression::CaseInsensitiveOption);
                                    static const QRegularExpression patMd5Re(
                                        QStringLiteral("<param\\b[^>]*?name=[\"']Texture/Pattern/PatternMD5[\"'][^>]*>(?:<!\\[CDATA\\[)?([A-Za-z0-9+/=\\r\\n]+)(?:\\]\\]>)?</param>"),
                                        QRegularExpression::CaseInsensitiveOption);
                                    auto mrMd5Sum = patMd5SumRe.match(xmlStr);
                                    auto mrMd5 = patMd5Re.match(xmlStr);
                                    const QString md5Hex = resolveMd5HexFromXml(
                                        mrMd5Sum.hasMatch() ? mrMd5Sum.captured(1) :
                                        (mrMd5.hasMatch() ? mrMd5.captured(1) : QString()));
                                    const QString patFile = !patFileName.isEmpty() ? patFileName : patName;
                                    KoPatternSP pat = registerMemoryPattern(patBytes, patName, patFile,
                                                                            md5Hex, m_brushResources);
                                    if (pat) {
                                        lr->addResource(KoResourceSP(pat));
                                        rememberLoadedPatternNames({patName, patFile,
                                                                    QFileInfo(patFile).completeBaseName()},
                                                                   m_loadedResourceNames);
                                    }
                                }
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
                    // S1 前置按名查缓存 (绕开 loadSingleBrushResource 的 isBrush 误判), 语义与
                    // loadSingleBrushResource 的缓存命中返回一致
                    if (memoryResourceAlreadyRegistered({sig.filename, QFileInfo(sig.filename).fileName(), sig.name},
                                                        m_loadedBrushes, m_loadedResourceNames)) {
                        addedSideloaded = true;
                    }
                    // 磁盘优先
                    else if (loadSingleBrushResource(sig.filename)) {
                        addedSideloaded = true;
                    } else {
                        // 磁盘缺失 → 直接消费内嵌字节内存构造补位 (不再转磁盘找文件)
                        const QByteArray bytes = er.data();
                        const QString md5Hex = resolveMd5HexFromXml(sig.md5sum);
                        const QString sigFile = QFileInfo(sig.filename).fileName();
                        const bool isGbr = sigFile.endsWith(QLatin1String(".gbr"), Qt::CaseInsensitive);
                        const bool isPat = sig.type == ResourceType::Patterns ||
                                           sigFile.endsWith(QLatin1String(".pat"), Qt::CaseInsensitive);
                        if (isPat) {
                            KoPatternSP pat = registerMemoryPattern(bytes, sig.name, sigFile,
                                                                    md5Hex, m_brushResources);
                            if (pat && lr) {
                                lr->addResource(KoResourceSP(pat));
                                rememberLoadedPatternNames({sig.name, sigFile,
                                                            QFileInfo(sigFile).completeBaseName()},
                                                           m_loadedResourceNames);
                                addedSideloaded = true;
                            }
                        } else if (sig.type == ResourceType::Brushes ||
                                   isGbr ||
                                   sigFile.endsWith(QLatin1String(".png"), Qt::CaseInsensitive)) {
                            KisBrushSP b = registerMemoryBrush(bytes, sigFile, md5Hex, isGbr,
                                                               m_brushResources);
                            if (b && lr) {
                                lr->addResource(b.staticCast<KoResource>());
                                // 原始 filename 带子目录时追加别名克隆 (仅换 filename,
                                // md5/name 不变), 使带子目录引用在无 md5 精确匹配时也能命中
                                if (!sig.filename.isEmpty() && sig.filename != sigFile) {
                                    KisBrushSP aliasSp = b->clone().dynamicCast<KisBrush>();
                                    if (aliasSp) {
                                        aliasSp->setFilename(sig.filename);
                                        lr->addResource(aliasSp.staticCast<KoResource>());
                                    }
                                }
                                rememberLoadedBrushNames({sig.filename, sigFile, QFileInfo(sigFile).completeBaseName(),
                                                          sig.name},
                                                         b, m_loadedBrushes);
                                if (!sig.filename.isEmpty()) m_loadedResourceNames.insert(sig.filename);
                                addedSideloaded = true;
                            }
                        }
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
    struct LoadingScope {
        bool &flag;
        explicit LoadingScope(bool &f) : flag(f) { flag = true; }
        ~LoadingScope() { flag = false; }
    } loadingScope(m_isLoadingPreset);
    m_presetIsEraserOverride = -1; // new preset: heuristic governs until UI asserts
    m_brushPreset = preset;
    m_brushPresetIndex = index;
    m_currentPresetPath = path;
    // 先注入再读尺寸: paintOpSize() 读到的是已绑定笔尖而非绑定落空时的 1.0 兜底
    if (m_brushPreset && m_brushPreset->settings()) {
        if (auto *bsEarly = dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data())) {
            injectBrushDefinitionProperties(bsEarly, m_brushResources, m_loadedBrushes);
            if (bsEarly->autoSpacingActive()) {
                m_brushSpacing = bsEarly->autoSpacingCoeff();
            } else {
                const double sp = bsEarly->spacing();
                if (sp > 0.0 && sp == sp) {
                    m_brushSpacing = sp;
                }
            }
        }
    }
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
    }
    setBrushSize(m_brushSize);
    setBrushOpacity(m_brushOpacity);
    setBrushFlow(m_brushFlow);
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
    if (bs) {
        // R6: 注入必须在 setBrushSize/Opacity/Flow 等所有 setProperty 执行完毕后作为最后一道收口门禁执行,
        // 防止被 onPropertyChanged() -> m_savedBrush.clear() 冲刷抹除
        injectBrushDefinitionProperties(bs, m_brushResources, m_loadedBrushes);
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
        KisBrushSP b = bs->brush();
        if (b) {
            // 不要调用 brushTipImage(): 对 IMAGESTAMP/LIGHTNESSMAP 或未惰性解码的笔尖会触发
            // Krita kis_brush.cpp 的 SAFE ASSERT (每次载预设都打栈回溯)。只读整型成员即可。
            RPC_LOG("RPC brushRESOLVED file=%s tip=%dx%d valid=%d spacing=%.3f auto=%d coeff=%.3f",
                    b->filename().toUtf8().constData(),
                    b->width(), b->height(), b->valid() ? 1 : 0,
                    (double)b->spacing(),
                    b->autoSpacingActive() ? 1 : 0,
                    (double)b->autoSpacingCoeff());
            if (reverie::isDegenerateTip(b->width(), b->height(), b->valid())) {
                RPC_LOG("RPC brushDEGENERATE tip=%dx%d valid=%d preset=%s (single-pixel / invalid tip: strokes will paint a speck)",
                        b->width(), b->height(), b->valid() ? 1 : 0,
                        m_currentPresetPath.toUtf8().constData());
            }
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
        if (b) {
            // 语义: 笔刷有效且笔尖尺寸非零。不调用 brushTipImage(), 避免触发 SAFE ASSERT。
            return b->valid() && b->width() > 0 && b->height() > 0;
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
                    // 有意与 loadBrushPresetCommon 不同: 此处不做内存构造补位。
                    // 本路径仅用于预设信息展示 (名字/缩略图), 展示无需解码内嵌字节,
                    // 真正的笔画用资源注册统一在 loadBrushPresetCommon 完成, 避免重复注册。
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

    // R5-A: 与 loadBrushPresetCommon 同款绑定注入, 保证信息提取路径读到的
    // spacing/autoSpacing 与实际笔画用的绑定笔刷一致 (否则 UI 出厂间距会退化成
    // 笔尖文件内嵌的线性值)
    injectBrushDefinitionProperties(
        dynamic_cast<KisBrushBasedPaintOpSettings *>(s.data()),
        m_brushResources, m_loadedBrushes);

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
        auto *bs = dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
        // 守卫: setPaintOpSize 在上游经 BrushWriter 读-改-写 brush_definition;
        // 绑定落空时会把 1x1 兜底 auto_brush 回写, 之后 injectBrushDefinitionProperties 因 auto_brush 早退
        const QString defBefore = bs ? bs->getString(QStringLiteral("brush_definition")) : QString();
        m_brushPreset->settings()->setPaintOpSize(v);
        if (bs) {
            const QString defAfter = bs->getString(QStringLiteral("brush_definition"));
            if (reverie::brushDefDowngraded(defBefore.toStdString(), defAfter.toStdString())) {
                RPC_LOG("RPC brushDEGENERATE downgraded %s -> auto_brush after setPaintOpSize(%.2f); restoring",
                        reverie::brushDefType(defBefore.toStdString()).c_str(), double(v));
                bs->setProperty(QStringLiteral("brush_definition"), defBefore);
                injectBrushDefinitionProperties(bs, m_brushResources, m_loadedBrushes);
                // 救援成功 (已绑定到池内带文件名的笔尖) 才把尺寸再写一次; 否则保持原定义不动
                KisBrushSP rescued = bs->brush();
                if (rescued && !rescued->filename().isEmpty()) {
                    m_brushPreset->settings()->setPaintOpSize(v);
                }
            }
        }
        // 尺寸变化会改变湿笔间距下限 (先选预设再把尺寸拉到 400px 的情况)
        // 预设加载过程中严禁调用: 加载收口已由 loadBrushPresetCommon 尾部统一完成, 此处调用会造成旧间距污染或覆盖
        if (!m_isLoadingPreset && bs && isColorSmudgePreset(m_brushPreset) && m_brushSpacing > 0.0) {
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

// 试画板异步 dab 管线的同步驱动: 逐批跑完 paintop 的渲染 job, 直到不再产出新 job。
// job 执行复用主画布 runStrokeJobsBatch 的保序并行语义 (reverieRunStrokeJobsBatch,
// 声明见 ReverieCoreInternal.h), 单条 job 仍按序, 不改变任何时序契约。
template <typename Fn>
static void scratchpadRunJobs(Fn fn, qreal brushSize, ReverieCore::ParallelStrokeJobsExecutor *executor = nullptr)
{
    if (executor) {
        executor->flushPendingJobs(brushSize);
    }
    while (true) {
        QVector<KisRunnableStrokeJobData *> jobs;
        auto result = fn(jobs);
        reverieRunStrokeJobsBatch(jobs, brushSize);
        if (jobs.isEmpty() || !result.second) {
            break;
        }
    }
}

void ReverieCore::scratchpadReleaseStroke()
{
    // 释放一笔的全部绘制状态 (蒙版链路先于 painter, 同主画布 endStrokeBatch 顺序)
    delete m_scratchpadMaskedPainter;
    m_scratchpadMaskedPainter = nullptr;
    delete m_scratchpadMaskStrokeInfo;
    m_scratchpadMaskStrokeInfo = nullptr;
    delete m_scratchpadMaskInfo;
    m_scratchpadMaskInfo = nullptr;
    delete m_scratchpadMaskRenderer;
    m_scratchpadMaskRenderer = nullptr;
    if (m_scratchpadPainter) {
        m_scratchpadPainter->end();
        delete m_scratchpadPainter;
        m_scratchpadPainter = nullptr;
    }
    if (m_scratchpadDistInfo) {
        delete m_scratchpadDistInfo;
        m_scratchpadDistInfo = nullptr;
    }
    m_scratchpadTempDev.clear();
    m_scratchpadIndirectCompOp.clear();
    m_scratchpadRandom.clear();
    m_scratchpadPerStrokeRandom.clear();
    m_scratchpadStrokeActive = false;
}

void ReverieCore::scratchpadAccumulateDirty(const QVector<QRect> &exactDirty, const QRect &fallbackRect)
{
    QRect exactUnion;
    for (const QRect &r : exactDirty) {
        m_scratchpadDirtyRect |= r;
        if (!r.isEmpty()) {
            exactUnion = exactUnion.isNull() ? r : exactUnion.united(r);
        }
    }
    // 部分引擎 (鬃毛/散布类) 的 takeDirtyRegion() 低报 (只回报几像素见方的中心块)
    // 或不报: 按它标脏会让试画板运笔期间缺墨。与主画布 flushStrokeBatch 同款判定,
    // 低报/不报时用调用方给的样本段/样本点矩形兜底标脏。
    const int minExpectedSpan = qMax(4, int(m_brushSize * 0.25));
    const bool underReported = !exactDirty.isEmpty() && m_brushSize >= 6.0 &&
        (exactUnion.width() < minExpectedSpan || exactUnion.height() < minExpectedSpan);
    if (fallbackRect.isValid() && (exactDirty.isEmpty() || underReported)) {
        m_scratchpadDirtyRect |= fallbackRect;
    }
}

void ReverieCore::ensureScratchpadInterstrokeData()
{
    if (!m_scratchpadDev || !m_brushPreset) {
        return;
    }
    KisPaintOpPresetSP preset = m_brushPreset;
    const bool isColorSmudge = (preset->paintOp().id() == QStringLiteral("colorsmudge")) ||
                               (m_toolMode == ToolSmudge);
    KisInterstrokeDataFactory *factory = nullptr;
    if (isColorSmudge && preset->paintOp().id() != QStringLiteral("colorsmudge")) {
        KisPaintOpFactory *smudgeFactory =
            KisPaintOpRegistry::instance()->get(QStringLiteral("colorsmudge"));
        if (smudgeFactory) {
            factory = smudgeFactory->createInterstrokeDataFactory(preset->settings(), m_brushResources);
        }
    } else {
        factory = KisPaintOpRegistry::instance()->createInterstrokeDataFactory(preset);
    }
    if (!factory) {
        detachScratchpadInterstrokeData();
        return;
    }
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
        detachScratchpadInterstrokeData();
        m_scratchpadDev = new KisPaintDevice(cs);
        m_scratchpadWidth = w;
        m_scratchpadHeight = h;
    }
    scratchpadReleaseStroke();
    m_scratchpadDev->clear();
    // 画布 (重) 建立后第一帧必须整幅重绘 (Kotlin 侧此时换了新 Bitmap, 旧内容不可信)
    m_scratchpadPreviewDev = nullptr;
    m_scratchpadDirtyRect = QRect(0, 0, m_scratchpadWidth, m_scratchpadHeight);
    m_scratchpadPrevTempRect = QRect();
    m_scratchpadFullDirty = true;
    return true;
}

bool ReverieCore::scratchpadStrokeStart(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation)
{
    if (!m_scratchpadDev || !m_brushPreset) return false;
    // 涂抹类预设策略要求设备上挂着 interstroke data (真笔画由事务注入, 预览没有事务)
    ensureScratchpadInterstrokeData();
    // 丢弃上一笔可能残留的每笔状态 (painter / 蒙版链路 / 间接暂存 / 随机源)
    scratchpadReleaseStroke();

    KisPaintOpSettingsSP s = m_brushPreset->settings();
    const QString paintOpId = m_brushPreset->paintOp().id();
    // 读取目标设备既有像素的引擎必须直绘 (同主画布 flushStrokeBatch):
    // 重定向到空白间接目标后读到的全是透明像素, 落笔等于空操作。
    const bool isSmudgeOp = paintOpId == QStringLiteral("colorsmudge");
    const bool readsLayerPixels = isSmudgeOp ||
        paintOpId == QStringLiteral("deformbrush") ||
        paintOpId == QStringLiteral("filter") ||
        paintOpId == QStringLiteral("duplicate");
    const bool hasMasking = !isSmudgeOp && m_brushPreset->hasMaskingPreset();
    const bool indirect = (!readsLayerPixels && s && !s->paintIncremental()) || hasMasking;

    const bool erasing = (m_presetIsEraserOverride == 1);
    QString effectiveOp = QStringLiteral("normal");
    if (erasing) {
        effectiveOp = QStringLiteral("erase");
    } else if (s) {
        effectiveOp = s->effectivePaintOpCompositeOp();
        if (effectiveOp.isEmpty() || effectiveOp == QLatin1String("erase")) {
            effectiveOp = QStringLiteral("normal");
        }
    }
    // 同主画布: 间接 dab 用 indirectPaintingCompositeOp, 直绘用 effectiveOp;
    // eraser 的 erase 落在合并阶段 (间接) 或直绘 compOp (直接)。
    const QString painterCompOp = indirect
        ? (s ? s->indirectPaintingCompositeOp() : QStringLiteral("alphadarken"))
        : effectiveOp;

    // 每笔时间基准与随机源 (与主画布 touchStrokeStart 同构, per-stroke 独立)
    m_scratchpadStrokeT0.start();
    m_scratchpadLastSampleSec = m_scratchpadStrokeT0.elapsed() / 1000.0;
    m_scratchpadRandom = new KisRandomSource(int(QRandomGenerator::global()->generate()));
    m_scratchpadPerStrokeRandom = new KisPerStrokeRandomSource();

    // 间接暂存设备: dab 先落 temp, 抬笔按 effectiveOp + m_brushOpacity 一次性合并
    if (indirect) {
        m_scratchpadTempDev = m_scratchpadDev->createCompositionSourceDevice();
        m_scratchpadIndirectCompOp = effectiveOp;
    }
    KisPaintDeviceSP target = indirect ? m_scratchpadTempDev : m_scratchpadDev;
    if (!target) {
        scratchpadReleaseStroke();
        return false;
    }

    // 节点始终包目标设备 (同主画布: painter 落 tempTarget, 节点是目标图层)
    KisNodeSP node = new KisPaintLayer(m_document, QStringLiteral("ScratchPad"), OPACITY_OPAQUE_U8, m_scratchpadDev);

    // 与主画布 flushStrokeBatch 的 indirect 挂载契约对齐: 间接笔程内把 temp 设备挂到
    // 节点上, paintop 创建 (setPaintOpPreset) 时 KisFlowOpacityOption2 检测到
    // node->hasTemporaryTarget() 会跳过逐 dab 的 OpacityValue 强度乘法 (只保留传感器
    // 曲线), 强度改由抬笔合并/实时预览的 m_brushOpacity 一次性承担, 消除
    // "逐 dab × 抬笔合并" 双乘。必须在下方两处 setPaintOpPreset 之前挂载 (选项在
    // paintop 构造时固化)。试画板无选区/alpha 锁, 不挂 TemporarySelection/ChannelFlags。
    if (indirect) {
        KisPaintLayer *scratchLayer = dynamic_cast<KisPaintLayer *>(node.data());
        if (scratchLayer) {
            m_scratchpadTempDev->setParentNode(scratchLayer);
            scratchLayer->setTemporaryTarget(m_scratchpadTempDev);
            scratchLayer->setTemporaryCompositeOp(effectiveOp);
            scratchLayer->setTemporaryOpacity(qBound<qreal>(0.0, m_brushOpacity, 1.0));
        }
    }

    const KoColorSpace *cs = target->colorSpace();
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) qColor = Qt::black;
    const KoColor koColor(qColor, cs);

    QColor qBgColor(m_brushSecondaryColor);
    if (!qBgColor.isValid()) qBgColor = Qt::white;
    const KoColor koBgColor(qBgColor, cs);

    const QPointF pt(x, y);
    m_scratchpadLastSample = StrokeSample{pt, pressure, tiltX, tiltY, rotation, 0.0};
    const reverie::StrokePaintSample startSample{pt, pressure, tiltX, tiltY, rotation, 0.0};
    const KisPaintInformation pi =
        reverie::makePaintInfo(startSample, 0.0, m_scratchpadRandom, m_scratchpadPerStrokeRandom);

    // 双笔刷蒙版链路兜底 (同主画布): 渲染器/蒙版预设任一不可用都退回普通单笔刷
    KisPaintOpPresetSP maskingPreset;
    if (hasMasking) {
        const QString maskingCompOpId = s
            ? s->maskingBrushCompositeOp()
            : QStringLiteral("alphadarken");
        m_scratchpadMaskRenderer = new KisMaskingBrushRenderer(target, maskingCompOpId);
        if (!m_scratchpadMaskRenderer->strokeDevice() || !m_scratchpadMaskRenderer->maskDevice()) {
            delete m_scratchpadMaskRenderer;
            m_scratchpadMaskRenderer = nullptr;
        } else {
            maskingPreset = m_brushPreset->createMaskingPreset();
            if (maskingPreset) {
                if (maskingPreset->settings()) {
                    injectBrushDefinitionProperties(
                        dynamic_cast<KisBrushBasedPaintOpSettings *>(maskingPreset->settings().data()),
                        m_brushResources, m_loadedBrushes);
                }
                // alpha8 掩膜下把 IMAGESTAMP 副笔刷的灰度烘进 alpha (同主画布创建点)
                maskingPreset = adaptMaskingPresetForAlpha8Mask(
                    maskingPreset, reverieMaskingBrushMaskIsAlpha(), m_brushResources.data());
                if (maskingPreset && maskingPreset->settings()) {
                    injectBrushDefinitionProperties(
                        dynamic_cast<KisBrushBasedPaintOpSettings *>(maskingPreset->settings().data()),
                        m_brushResources, m_loadedBrushes);
                }
            }
            if (!maskingPreset) {
                delete m_scratchpadMaskRenderer;
                m_scratchpadMaskRenderer = nullptr;
            }
        }
    }

    if (m_scratchpadMaskRenderer && maskingPreset) {
        KisDistanceInformation startDist(pt, 0.0);
        m_scratchpadMaskStrokeInfo = new KisFreehandStrokeInfo(startDist);
        m_scratchpadMaskInfo = new KisFreehandStrokeInfo(startDist);

        KisPainter *strokeP = m_scratchpadMaskStrokeInfo->painter;
        strokeP->begin(m_scratchpadMaskRenderer->strokeDevice(), nullptr);
        strokeP->setRunnableStrokeJobsInterface(&m_fakeExecutor);
        strokeP->setFillStyle(KisPainter::FillStyleForegroundColor);
        strokeP->setStrokeStyle(KisPainter::StrokeStyleBrush);
        strokeP->setCompositeOpId(QStringLiteral("alphadarken"));
        strokeP->setOpacityToUnit();
        strokeP->setPaintColor(koColor);
        strokeP->setBackgroundColor(koBgColor);
        strokeP->setChannelFlags(QBitArray());
        strokeP->setPaintOpPreset(m_brushPreset, node, m_document);

        KisPainter *maskP = m_scratchpadMaskInfo->painter;
        maskP->begin(m_scratchpadMaskRenderer->maskDevice(), nullptr);
        maskP->setRunnableStrokeJobsInterface(&m_fakeExecutor);
        maskP->setFillStyle(KisPainter::FillStyleForegroundColor);
        maskP->setStrokeStyle(KisPainter::StrokeStyleBrush);
        maskP->setCompositeOpId(QStringLiteral("alphadarken"));
        maskP->setOpacityToUnit();
        maskP->setPaintColor(KoColor(Qt::white, maskP->device()->colorSpace()));
        maskP->setBackgroundColor(KoColor(Qt::black, maskP->device()->colorSpace()));
        maskP->setChannelFlags(QBitArray());
        maskP->setPaintOpPreset(maskingPreset, node, m_document);

        m_scratchpadMaskedPainter = new KisMaskedFreehandStrokePainter(m_scratchpadMaskStrokeInfo, m_scratchpadMaskInfo);
        m_scratchpadMaskedPainter->paintAt(pi);
        scratchpadRunJobs([this](QVector<KisRunnableStrokeJobData *> &jobs) {
            return m_scratchpadMaskedPainter->doAsynchronousUpdate(jobs);
        }, m_brushSize, &m_fakeExecutor);
        if (m_scratchpadMaskRenderer) {
            m_scratchpadDirtyRect |= reverieMaskingBrushUpdateProjection(
                m_scratchpadMaskRenderer, m_scratchpadMaskedPainter->takeDirtyRegion());
        }
    } else {
        m_scratchpadPainter = new KisPainter(target);
        m_scratchpadPainter->setRunnableStrokeJobsInterface(&m_fakeExecutor);
        m_scratchpadPainter->setPaintOpPreset(m_brushPreset, node, m_document);
        m_scratchpadPainter->setPaintColor(koColor);
        m_scratchpadPainter->setBackgroundColor(koBgColor);
        // 墨量由引擎 OpacityValue 管, painter 恒 1.0 (消除双乘, 同主画布)
        m_scratchpadPainter->setOpacityF(1.0);
        m_scratchpadPainter->setCompositeOpId(painterCompOp.isEmpty() ? QStringLiteral("normal") : painterCompOp);

        m_scratchpadDistInfo = new KisDistanceInformation(pt, 0.0);
        m_scratchpadPainter->paintAt(pi, m_scratchpadDistInfo);
        if (KisPaintOp *op = m_scratchpadPainter->paintOp()) {
            scratchpadRunJobs([op](QVector<KisRunnableStrokeJobData *> &jobs) {
                return op->doAsynchronousUpdate(jobs);
            }, m_brushSize, &m_fakeExecutor);
        }
        // 普通路径脏区累计 (落笔 dab); 引擎不报/低报时用落笔点矩形兜底
        const int fw = int(m_brushSize) + 2;
        scratchpadAccumulateDirty(m_scratchpadPainter->takeDirtyRegion(),
                                  QRect(int(pt.x()) - fw, int(pt.y()) - fw, 2 * fw, 2 * fw));
    }

    m_scratchpadStrokeActive = true;
    return true;
}

bool ReverieCore::scratchpadStrokeMove(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation)
{
    if (!m_scratchpadStrokeActive) return false;
    const bool masked = m_scratchpadMaskedPainter != nullptr;
    if (!masked && (!m_scratchpadPainter || !m_scratchpadDistInfo)) return false;

    const QPointF pt(x, y);
    // 同批多点经 JNI 连调时墙钟间隔微秒级, speedPxPerMs 的 1e-4s 下限会把速度
    // 放大到 dist/1e-4 量级; 每点至少相对上一采样步进 4ms (近似真实采样率),
    // 真实批间间隔大于 4ms 时仍取墙钟, 静止 (dist=0) 速度不受影响。
    const qreal wallSec = m_scratchpadStrokeT0.elapsed() / 1000.0;
    const qreal timeSec = qMax(wallSec, m_scratchpadLastSampleSec + 0.004);
    m_scratchpadLastSampleSec = timeSec;
    const reverie::StrokePaintSample sampleA{
        m_scratchpadLastSample.imgPos, m_scratchpadLastSample.pressure,
        m_scratchpadLastSample.tiltX, m_scratchpadLastSample.tiltY,
        m_scratchpadLastSample.rotation, m_scratchpadLastSample.time};
    const reverie::StrokePaintSample sampleB{pt, pressure, tiltX, tiltY, rotation, timeSec};
    // 速度 px/ms, 与 Krita 传感器刻度对齐 (同主画布)
    const qreal speed = reverie::speedBetween(sampleA, sampleB);
    const KisPaintInformation pi1 =
        reverie::makePaintInfo(sampleA, speed, m_scratchpadRandom, m_scratchpadPerStrokeRandom);
    const KisPaintInformation pi2 =
        reverie::makePaintInfo(sampleB, speed, m_scratchpadRandom, m_scratchpadPerStrokeRandom);

    if (masked) {
        m_scratchpadMaskedPainter->paintLine(pi1, pi2);
        scratchpadRunJobs([this](QVector<KisRunnableStrokeJobData *> &jobs) {
            return m_scratchpadMaskedPainter->doAsynchronousUpdate(jobs);
        }, m_brushSize, &m_fakeExecutor);
        if (m_scratchpadMaskRenderer) {
            m_scratchpadDirtyRect |= reverieMaskingBrushUpdateProjection(
                m_scratchpadMaskRenderer, m_scratchpadMaskedPainter->takeDirtyRegion());
        }
    } else {
        m_scratchpadPainter->paintLine(pi1, pi2, m_scratchpadDistInfo);
        if (KisPaintOp *op = m_scratchpadPainter->paintOp()) {
            scratchpadRunJobs([op](QVector<KisRunnableStrokeJobData *> &jobs) {
                return op->doAsynchronousUpdate(jobs);
            }, m_brushSize, &m_fakeExecutor);
        }
        // 普通路径脏区累计 (样本段逐段标脏, 同主画布不并整批包围盒);
        // 引擎不报/低报时用上一采样点→当前点的样本段矩形兜底
        const int fw = int(m_brushSize) + 2;
        const QPointF &pa = m_scratchpadLastSample.imgPos;
        const int minX = int(qMin(pa.x(), pt.x()));
        const int maxX = int(qMax(pa.x(), pt.x()));
        const int minY = int(qMin(pa.y(), pt.y()));
        const int maxY = int(qMax(pa.y(), pt.y()));
        scratchpadAccumulateDirty(m_scratchpadPainter->takeDirtyRegion(),
                                  QRect(minX - fw, minY - fw, (maxX - minX) + 2 * fw, (maxY - minY) + 2 * fw));
    }

    m_scratchpadLastSample = StrokeSample{pt, pressure, tiltX, tiltY, rotation, timeSec};
    return true;
}

void ReverieCore::scratchpadStrokeEnd()
{
    // 收尾 flush: 残余 dab 先落墨并合进间接暂存 (蒙版链路在删除 painter 前取脏区)
    if (m_scratchpadMaskedPainter) {
        scratchpadRunJobs([this](QVector<KisRunnableStrokeJobData *> &jobs) {
            return m_scratchpadMaskedPainter->doAsynchronousUpdate(jobs);
        }, m_brushSize, &m_fakeExecutor);
        if (m_scratchpadMaskRenderer) {
            m_scratchpadDirtyRect |= reverieMaskingBrushUpdateProjection(
                m_scratchpadMaskRenderer, m_scratchpadMaskedPainter->takeDirtyRegion());
        }
    } else if (m_scratchpadPainter) {
        if (KisPaintOp *op = m_scratchpadPainter->paintOp()) {
            scratchpadRunJobs([op](QVector<KisRunnableStrokeJobData *> &jobs) {
                return op->doAsynchronousUpdate(jobs);
            }, m_brushSize, &m_fakeExecutor);
        }
        // 普通路径收尾脏区累计; 引擎不报时用最后采样点矩形兜底
        const int fw = int(m_brushSize) + 2;
        const QPointF &p0 = m_scratchpadLastSample.imgPos;
        scratchpadAccumulateDirty(m_scratchpadPainter->takeDirtyRegion(),
                                  QRect(int(p0.x()) - fw, int(p0.y()) - fw, 2 * fw, 2 * fw));
    }

    // 间接暂存合并 (同主画布 touchStrokeEnd 的 Finalize Indirect Painting):
    // 以 effectiveOp + m_brushOpacity 一次性 bitBlt 到试画板设备
    if (m_scratchpadTempDev && m_scratchpadDev) {
        const QRect ext = m_scratchpadTempDev->exactBounds();
        if (!ext.isEmpty()) {
            KisPainter gc(m_scratchpadDev);
            gc.setCompositeOpId(m_scratchpadIndirectCompOp.isEmpty()
                ? QStringLiteral("normal") : m_scratchpadIndirectCompOp);
            gc.setOpacityF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
            gc.bitBlt(ext.topLeft(), m_scratchpadTempDev, ext);
            gc.end();
        }
        // 上一帧 render 已把 tempDev 预览写进 Bitmap: 合并后该区域的最终像素可能不同
        // (erase/透明度合并等), 必须连旧预览区一起标脏重绘 (scratchpadReleaseStroke
        // 不清 m_scratchpadDirtyRect, 留给紧随的 render 消费)
        m_scratchpadDirtyRect |= ext | m_scratchpadPrevTempRect;
    }

    scratchpadReleaseStroke();
}

void ReverieCore::scratchpadClear()
{
    if (m_scratchpadDev) {
        m_scratchpadDev->clear();
    }
    // 清空后下一帧 render 必须整幅重绘 (Kotlin 侧同时 eraseColor 了 Bitmap,
    // 但在途的合帧 render 可能晚于清空执行, 全量脏区保证写回的一定是清空后内容)
    m_scratchpadDirtyRect = QRect(0, 0, m_scratchpadWidth, m_scratchpadHeight);
    m_scratchpadFullDirty = true;
}

bool ReverieCore::scratchpadRender(quint8 *buffer, int w, int h, int stride)
{
    if (!m_scratchpadDev || !buffer || w <= 0 || h <= 0 || stride < w * 4) return false;
    const int rw = qMin(w, m_scratchpadWidth);
    const int rh = qMin(h, m_scratchpadHeight);
    const QRect bounds(0, 0, rw, rh);

    // 间接笔程 (paintIncremental=false / 蒙版笔) 进行中时 dab 落在 m_scratchpadTempDev,
    // 抬笔才合并; 实时预览需按 strokeEnd 同款参数 (effectiveOp + m_brushOpacity)
    // 把 tempDev 叠进持久预览设备, 保证"预览=最终落盘"。
    QRect curTempExt;
    if (m_scratchpadStrokeActive && m_scratchpadTempDev) {
        curTempExt = m_scratchpadTempDev->exactBounds().intersected(bounds);
    }

    // 本帧只需重绘: 累积落墨脏区 ∪ 当前 temp 区 ∪ 上一帧 temp 区 (残影防御)。
    // takeDirtyRect 消费语义: 取走即清, 下一帧从零累计。
    QRect dirty = m_scratchpadFullDirty
        ? bounds
        : (m_scratchpadDirtyRect | curTempExt | m_scratchpadPrevTempRect).intersected(bounds);
    m_scratchpadDirtyRect = QRect();
    m_scratchpadFullDirty = false;

    if (dirty.isEmpty()) {
        m_scratchpadPrevTempRect = curTempExt;
        return true;
    }

    KisPaintDeviceSP srcDev = m_scratchpadDev;
    if (m_scratchpadStrokeActive && m_scratchpadTempDev) {
        if (!m_scratchpadPreviewDev ||
            *m_scratchpadPreviewDev->colorSpace() != *m_scratchpadDev->colorSpace()) {
            m_scratchpadPreviewDev = new KisPaintDevice(m_scratchpadDev->colorSpace());
        }
        // 只在 dirty 矩形内重建预览: 先用 COPY 从底图完整还原 (含上一帧 temp 残留区),
        // 再叠加当前帧 tempDev, 预览设备其余区域保留上一帧结果 (增量, 非全画布深拷贝)
        m_scratchpadPreviewDev->clear(dirty);
        {
            KisPainter copyGc(m_scratchpadPreviewDev);
            copyGc.setCompositeOpId(COMPOSITE_COPY);
            copyGc.bitBlt(dirty.topLeft(), m_scratchpadDev, dirty);
            copyGc.end();
        }
        const QRect tempDirty = curTempExt.intersected(dirty);
        if (!tempDirty.isEmpty()) {
            KisPainter gc(m_scratchpadPreviewDev);
            gc.setCompositeOpId(m_scratchpadIndirectCompOp.isEmpty()
                ? QStringLiteral("normal") : m_scratchpadIndirectCompOp);
            gc.setOpacityF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
            gc.bitBlt(tempDirty.topLeft(), m_scratchpadTempDev, tempDirty);
            gc.end();
        }
        srcDev = m_scratchpadPreviewDev;
        m_scratchpadPrevTempRect = curTempExt;
    } else {
        m_scratchpadPrevTempRect = QRect();
    }

    // 只读回 dirty 子矩形, 按步长偏移直写 Bitmap 对应区域 (锁定的 buffer 保留
    // 上一帧非脏区像素, 与 Kotlin 侧"试画板生命周期内同一 Bitmap 实例"前提配套)
    const int dw = dirty.width();
    const int dh = dirty.height();
    const int neededBytes = dw * dh * 4;
    if (m_scratchpadReadBuf.size() < neededBytes) {
        m_scratchpadReadBuf.resize(neededBytes);
    }
    srcDev->readBytes(reinterpret_cast<quint8 *>(m_scratchpadReadBuf.data()),
                      dirty.x(), dirty.y(), dw, dh);
    uint8_t *dstSub = buffer + size_t(dirty.y()) * size_t(stride) + size_t(dirty.x()) * 4;
    PixelAlpha::toDisplayRows(
        reinterpret_cast<const uint8_t *>(m_scratchpadReadBuf.constData()),
        dw * 4, dstSub, stride, dw, dh);
    return true;
}

void ReverieCore::scratchpadEnd()
{
    scratchpadReleaseStroke();
    m_scratchpadDev = nullptr;
    m_scratchpadWidth = 0;
    m_scratchpadHeight = 0;
    m_scratchpadPreviewDev = nullptr;
    m_scratchpadReadBuf.clear();
    m_scratchpadDirtyRect = QRect();
    m_scratchpadPrevTempRect = QRect();
    m_scratchpadFullDirty = true;
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



