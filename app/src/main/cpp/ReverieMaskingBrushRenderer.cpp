/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ReverieMaskingBrushRenderer.cpp - Android 定制版 KisMaskingBrushRenderer。
 *
 * 本文件是 Krita libs/ui/tool/strokes/KisMaskingBrushRenderer.cpp 的本地副本,
 * 唯一区别是为掩膜设备的色彩空间增加了空指针兜底 (见下方注释)。
 *
 * 背景 (水彩类预设落笔必崩的根因):
 *   上游实现无条件使用
 *       KoColorSpaceRegistry::instance()->colorSpace(GrayAColorModelID.id(),
 *                                                    Integer8BitsColorDepthID.id())
 *   该灰度色彩空间由 lcms2 色彩引擎插件提供, 并依赖随包发布的
 *   Gray-D50-elle-V2-srgbtrc.icc。Android 构建既不加载该插件也不内置该 ICC,
 *   KoColorSpaceRegistry 里只有 Alpha8 / AlphaU16 / AlphaF32 / Lab / RgbU8 /
 *   RgbU16 六个内置空间, 因此查询返回 nullptr;
 *   new KisPaintDevice(nullptr) 随后在 KoColorSpaceRegistry::permanentColorspace
 *   处解引用空指针, 进程 SIGSEGV 闪退。
 *
 * 兜底策略:
 *   GrayA 不可用时改用内置的 alpha8() (KisAlphaColorSpace)。蒙版合成算子读取的
 *   是掩膜像素的第一个字节 —— GrayA 下为 gray, alpha8 下为 alpha;而掩膜笔刷以
 *   白色 (alpha=255) 涂布, 两者语义完全等价 (gray*alpha == alpha)。
 *   同时改用 KisMaskingBrushCompositeOpFactory::createForAlphaSrc(), 该分支以
 *   mask_is_alpha=true 实例化算子, 正是为 alpha 掩膜准备的官方路径。
 */

#include "KisMaskingBrushRenderer.h"

#include <KoColorSpace.h>
#include <KoColorSpaceRegistry.h>
#include <KoColorModelStandardIds.h>
#include <KoChannelInfo.h>
#include <KoCompositeOpRegistry.h>

#include <algorithm>

#include <android/log.h>
#include <kis_assert.h>

#include "kis_painter.h"
#include "kis_paint_device.h"
#include "kis_random_accessor_ng.h"

#include "KisMaskingBrushCompositeOpBase.h"
#include "KisMaskingBrushCompositeOpFactory.h"

#include <brushengine/kis_paintop_preset.h>
#include <brushengine/kis_paintop_settings.h>
#include <kis_brush.h>
#include <kis_properties_configuration.h>
#include <KisResourcesInterface.h>
#include <KisLocalStrokeResources.h>
#include <KisResourceTypes.h>
#include <QCryptographicHash>
#include <QDomDocument>
#include <QGlobalStatic>
#include <QHash>

#define MBR_LOG_TAG "RP_MASKING"
#define MBR_LOGW(...) __android_log_print(ANDROID_LOG_WARN, MBR_LOG_TAG, __VA_ARGS__)

namespace {

/**
 * 掩膜设备色彩空间: 首选 Krita 原生的 GrayA/U8, 缺失时回退到内置 alpha8。
 * 返回值保证非空 (alpha8 是 pigment 的内建空间, 始终可用)。
 * @param maskIsAlpha 出参: true 表示返回的色彩空间是纯 alpha 语义。
 */
const KoColorSpace *maskingBrushMaskColorSpace(bool *maskIsAlpha)
{
    KoColorSpaceRegistry *registry = KoColorSpaceRegistry::instance();
    const KoColorSpace *cs = registry->colorSpace(GrayAColorModelID.id(),
                                                  Integer8BitsColorDepthID.id());
    if (cs) {
        if (maskIsAlpha) *maskIsAlpha = false;
        return cs;
    }

    // Android: 无 lcms2 插件 / 无 Gray ICC → 回退 alpha8 (语义等价, 见文件头)。
    cs = registry->alpha8();
    if (cs) {
        if (maskIsAlpha) *maskIsAlpha = true;
        return cs;
    }

    // 理论上不可达: alpha8 是 pigment 内建空间。真到这里时再退到 RGBA8,
    // 保证 KisPaintDevice 永远拿不到 nullptr。
    cs = registry->rgb8();
    if (maskIsAlpha) *maskIsAlpha = false;
    return cs;
}

} // namespace

bool reverieMaskingBrushMaskIsAlpha()
{
    bool maskIsAlpha = false;
    maskingBrushMaskColorSpace(&maskIsAlpha);
    return maskIsAlpha;
}

namespace {

/**
 * 已适配的克隆笔刷缓存: 键 = 原笔尖签名 (filename|md5|brushApplication)。
 * masking preset 每笔重建, 若每笔都克隆一张笔尖级 QImage 并注册进 m_brushResources
 * (会话级不回收), 长时间绘画会造成无界内存增长; 以签名去重后整个会话每种笔尖至多克隆一次。
 */
struct GrayMaskCloneCache {
    QHash<QString, KisBrushSP> clones;
};
Q_GLOBAL_STATIC(GrayMaskCloneCache, g_grayMaskCloneCache)

} // namespace

KisPaintOpPresetSP adaptMaskingPresetForAlpha8Mask(KisPaintOpPresetSP maskingPreset,
                                                   bool maskIsAlpha,
                                                   KisResourcesInterface *resources)
{
    // GrayA 掩膜可用 (maskIsAlpha==false) 时上游语义本来就正确, 将来 GrayA 恢复后自动短路
    if (!maskingPreset || !maskIsAlpha || !resources) return maskingPreset;

    KisPaintOpSettingsSP settings = maskingPreset->settings();
    if (!settings) return maskingPreset;

    // 克隆笔刷必须注册进副笔刷解析所用的同一资源接口, 否则 stroke 时
    // KisBrush::fromXML → bestMatch 找不到新签名, 笔画会退化为默认 auto_brush
    KisLocalStrokeResources *lr = dynamic_cast<KisLocalStrokeResources *>(resources);
    if (!lr) {
        MBR_LOGW("masking brush: resources interface is not KisLocalStrokeResources, skip gray adaptation");
        return maskingPreset;
    }

    KisResourcesInterfaceSP resIface = settings->resourcesInterface();
    if (!resIface) return maskingPreset;

    // 副笔刷引用: 上游 getPrefixedProperties 已把 "MaskingBrush/Preset/brush_definition"
    // 拷贝为 masking settings 的无前缀键 "brush_definition" (XML 字符串)
    const QString brushDefinition = settings->getString("brush_definition");
    if (brushDefinition.isEmpty()) return maskingPreset;

    QDomDocument doc;
    if (!doc.setContent(brushDefinition)) return maskingPreset;
    QDomElement element = doc.firstChildElement(QStringLiteral("Brush"));
    if (element.isNull()) return maskingPreset;

    // ALPHAMASK 路径没有灰度丢失问题 (dab 掩膜直接取笔尖 alpha), 只有
    // IMAGESTAMP 才走 convertFromQImage 被 KoAlphaColorSpace::fromQColor 丢灰度
    KisBrushSP subBrush = KisBrush::fromXML(element, resIface);
    if (!subBrush || subBrush->brushApplication() != IMAGESTAMP) return maskingPreset;

    const QImage tip = subBrush->brushTipImage();
    if (tip.isNull() || tip.width() <= 0 || tip.height() <= 0) {
        MBR_LOGW("masking brush: IMAGESTAMP tip unavailable, skip gray adaptation");
        return maskingPreset;
    }

    const QString cacheKey = subBrush->filename() + QLatin1Char('|')
        + element.attribute(QStringLiteral("md5sum")) + QLatin1Char('|')
        + QString::number(int(subBrush->brushApplication()));

    KisBrushSP clone;
    if (g_grayMaskCloneCache) {
        clone = g_grayMaskCloneCache->clones.value(cacheKey);
    }

    if (!clone) {
        // 把灰度搬运进 alpha: a' = qGray(r,g,b) * a / 255, 像素写为白色 + a'。
        // 与上游 GrayA 掩膜设备的 preprocessMask = gray*alpha 语义一致
        // (KisMaskingBrushCompositeOp.h GrayA 分支), 白色 = 全强度, 黑色 = 无。
        const QImage src = tip.convertToFormat(QImage::Format_ARGB32);
        if (src.isNull()) {
            MBR_LOGW("masking brush: tip convertToFormat failed, skip gray adaptation");
            return maskingPreset;
        }

        QImage processed(src.size(), QImage::Format_ARGB32);
        processed.fill(0);
        bool anyVisible = false;
        for (int y = 0; y < src.height(); ++y) {
            const QRgb *srcLine = reinterpret_cast<const QRgb *>(src.constScanLine(y));
            QRgb *dstLine = reinterpret_cast<QRgb *>(processed.scanLine(y));
            for (int x = 0; x < src.width(); ++x) {
                const QRgb p = srcLine[x];
                const int gray = qGray(qRed(p), qGreen(p), qBlue(p));
                const int na = qBound(0, gray * qAlpha(p) / 255, 255);
                dstLine[x] = qRgba(255, 255, 255, na);
                if (na != 0) anyVisible = true;
            }
        }
        if (!anyVisible) {
            MBR_LOGW("masking brush: tip fully transparent after gray adaptation, skip");
            return maskingPreset;
        }

        clone = subBrush->clone().dynamicCast<KisBrush>();
        if (!clone) {
            MBR_LOGW("masking brush: clone sub brush failed, skip gray adaptation");
            return maskingPreset;
        }

        // 新 filename + 重算 md5: bestMatch 按 md5 优先命中, 若沿用原 md5 会先撞上
        // 已注册的原笔刷 (filename 不匹配只罚 2 分), 克隆就永远选不中
        const QString newFilename = subBrush->filename().isEmpty()
            ? QStringLiteral("graymask_tip")
            : subBrush->filename() + QStringLiteral("_graymask");
        const QString newMd5 = QString::fromLatin1(
            QCryptographicHash::hash(
                QByteArray(reinterpret_cast<const char *>(processed.constBits()),
                           static_cast<int>(processed.sizeInBytes())),
                QCryptographicHash::Md5).toHex());

        clone->setBrushTipImage(processed);
        clone->setFilename(newFilename);
        clone->setMD5Sum(newMd5);
        if (!clone->name().isEmpty()) {
            clone->setName(clone->name() + QStringLiteral(" (graymask)"));
        }

        if (g_grayMaskCloneCache) {
            g_grayMaskCloneCache->clones.insert(cacheKey, clone);
        }
    }

    // 同一 lr 会话内只注册一次; lr 重建 (新会话) 后 bestMatch 落空自动补注册
    const QString newFilename = clone->filename();
    const QString newMd5 = clone->md5Sum();
    KoResourceSP existing = lr->source<KisBrush>(ResourceType::Brushes)
                                .bestMatch(newMd5, newFilename, QString());
    if (!existing) {
        lr->addResource(clone.staticCast<KoResource>());
    }

    // settings 属性改指克隆: 其余属性 (spacing/angle/scale/ColorAsMask 等) 原样保留,
    // brushApplication 显式锁 IMAGESTAMP —— 必须走 convertFromQImage 路径只取笔尖
    // alpha, 才能把烘焙进 alpha 的灰度原样写进 alpha8 掩膜; 不能落到 ALPHAMASK
    // (实色路径 fillGrayBrushWithColor 的强度 = (255-red)*alpha, 白色笔尖会得全零
    // 掩膜), 也防克隆笔尖变纯白后 hasColorAndTransparency 被判 false 令解析分支漂移
    element.setAttribute(QStringLiteral("filename"), newFilename);
    element.setAttribute(QStringLiteral("md5sum"), newMd5);
    element.setAttribute(QStringLiteral("brushApplication"), QString::number(int(IMAGESTAMP)));
    settings->setProperty(QStringLiteral("brush_definition"), doc.toString());

    MBR_LOGW("masking brush: gray adapted tip '%s' -> '%s'",
             subBrush->filename().toUtf8().constData(),
             newFilename.toUtf8().constData());

    return maskingPreset;
}

KisMaskingBrushRenderer::KisMaskingBrushRenderer(KisPaintDeviceSP dstDevice, const QString &compositeOpId)
    : m_dstDevice(dstDevice)
{
    m_strokeDevice = new KisPaintDevice(dstDevice->colorSpace());

    bool maskIsAlpha = false;
    const KoColorSpace *maskColorSpace = maskingBrushMaskColorSpace(&maskIsAlpha);
    m_maskDevice = new KisPaintDevice(maskColorSpace);

    m_strokeDevice->setDefaultBounds(dstDevice->defaultBounds());
    m_maskDevice->setDefaultBounds(dstDevice->defaultBounds());

    const KoColorSpace *dstCs = m_dstDevice->colorSpace();
    const int pixelSize = dstCs->pixelSize();

    KoChannelInfo::enumChannelValueType alphaChannelType = KoChannelInfo::UINT8;
    int alphaChannelOffset = -1;
    int alphaPos = dstCs->alphaPos();

    KIS_SAFE_ASSERT_RECOVER (alphaPos >= 0) {
        alphaPos = 0;
    }

    const QList<KoChannelInfo *> channels = dstCs->channels();
    alphaChannelOffset = channels[alphaPos]->pos()/* * channels[i]->size()*/;
    alphaChannelType = channels[alphaPos]->channelValueType();

    KIS_SAFE_ASSERT_RECOVER (alphaChannelOffset >= 0) {
        alphaChannelOffset = 0;
    }

    // alpha 掩膜必须走 createForAlphaSrc(): 该分支以 mask_is_alpha=true 实例化
    // 合成算子, 直接读取掩膜像素的首字节 (即 alpha) 作为蒙版强度。
    m_compositeOp.reset(
        maskIsAlpha
            ? KisMaskingBrushCompositeOpFactory::createForAlphaSrc(
                  compositeOpId, alphaChannelType, pixelSize, alphaChannelOffset)
            : KisMaskingBrushCompositeOpFactory::create(
                  compositeOpId, alphaChannelType, pixelSize, alphaChannelOffset));

    if (!m_compositeOp) {
        MBR_LOGW("masking brush: composite op '%s' unavailable, falling back to MULT",
                 compositeOpId.toUtf8().constData());
        m_compositeOp.reset(
            maskIsAlpha
                ? KisMaskingBrushCompositeOpFactory::createForAlphaSrc(
                      COMPOSITE_MULT, alphaChannelType, pixelSize, alphaChannelOffset)
                : KisMaskingBrushCompositeOpFactory::create(
                      COMPOSITE_MULT, alphaChannelType, pixelSize, alphaChannelOffset));
    }
}

KisMaskingBrushRenderer::~KisMaskingBrushRenderer()
{
}

KisPaintDeviceSP KisMaskingBrushRenderer::strokeDevice() const
{
    return m_strokeDevice;
}

KisPaintDeviceSP KisMaskingBrushRenderer::maskDevice() const
{
    return m_maskDevice;
}

void KisMaskingBrushRenderer::updateProjection(const QRect &rc)
{
    if (rc.isEmpty()) return;

    KisPainter::copyAreaOptimized(rc.topLeft(), m_strokeDevice, m_dstDevice, rc);

    KisRandomAccessorSP dstIt = m_dstDevice->createRandomAccessorNG();
    KisRandomConstAccessorSP maskIt = m_maskDevice->createRandomConstAccessorNG();

    qint32 dstY = rc.y();
    qint32 rowsRemaining = rc.height();

    while (rowsRemaining > 0) {
        qint32 dstX = rc.x();

        const qint32 numContiguousDstRows = dstIt->numContiguousRows(dstY);
        const qint32 numContiguousMaskRows = maskIt->numContiguousRows(dstY);

        const qint32 rows = std::min({rowsRemaining, numContiguousDstRows, numContiguousMaskRows});

        qint32 columnsRemaining = rc.width();

        while (columnsRemaining > 0) {

            const qint32 numContiguousDstColumns = dstIt->numContiguousColumns(dstX);
            const qint32 numContiguousMaskColumns = maskIt->numContiguousColumns(dstX);
            const qint32 columns = std::min({columnsRemaining, numContiguousDstColumns, numContiguousMaskColumns});

            const qint32 dstRowStride = dstIt->rowStride(dstX, dstY);
            const qint32 maskRowStride = maskIt->rowStride(dstX, dstY);

            dstIt->moveTo(dstX, dstY);
            maskIt->moveTo(dstX, dstY);

            m_compositeOp->composite(maskIt->rawDataConst(), maskRowStride,
                                     dstIt->rawData(), dstRowStride,
                                     columns, rows);

            dstX += columns;
            columnsRemaining -= columns;
        }

        dstY += rows;
        rowsRemaining -= rows;
    }
}
