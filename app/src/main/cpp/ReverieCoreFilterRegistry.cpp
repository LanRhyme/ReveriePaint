/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ReverieCoreFilterRegistry.cpp - 滤镜像素内核的 KisFilter 注册层。
 *
 * 把 ReverieCoreFilterKernels 的 35 个内核 (id 0-34, 含 FX 与曲线LUT/渐变映射)
 * 以 "reverie-f<N>" 为 ID 注册进 KisFilterRegistry, 使 KisAsyncMerger 能够在
 * KisAdjustmentLayer / KisFilterMask 上原生驱动它们 —— 这是上一次调整图层实验
 * 回滚的直接修正: merger 只认注册表滤镜, 未注册的 ID 会得到透明输出。
 *
 * 注意: 既有单层滤镜预览管线 (ReverieCoreFilterPreview.cpp 三步备份法) 保持不动;
 * 本文件仅供非破坏性节点使用。processImpl 采用"外扩 margin 读入 → 内核 → 仅写回
 * 脏区"策略, 保证运笔期间增量合并只付出脏区+margin 的代价而非全图重算。
 */
#include "ReverieCoreInternal.h"

#include <filter/kis_filter.h>
#include <filter/kis_filter_registry.h>
#include <filter/kis_filter_category_ids.h>
#include <filter/kis_filter_configuration.h>

#include "ReverieCoreFilterKernels.h"

#include <QByteArray>
#include <QImage>
#include <QVariant>

namespace {

class ReverieKernelFilter : public KisFilter
{
public:
    explicit ReverieKernelFilter(int type)
        : KisFilter(idFor(type), categoryFor(type),
                    i18n("Reverie Filter %1").arg(type))
        , m_type(type)
    {
        setSupportsPainting(false);
        setSupportsAdjustmentLayers(true);
        setSupportsLevelOfDetail(false);
        setColorSpaceIndependence(FULLY_INDEPENDENT);
    }

    static KoID idFor(int type)
    {
        return KoID(QStringLiteral("reverie-f%1").arg(type),
                    i18n("Reverie Filter %1").arg(type));
    }

    static KoID categoryFor(int type)
    {
        switch (type) {
        case 2: case 3: case 33: return FiltersCategoryBlurId;
        case 4: case 18: case 19: case 22: case 26: return FiltersCategoryEnhanceId;
        case 5: case 10: case 21: case 23: case 34: return FiltersCategoryArtisticId;
        case 7: case 20: case 29: case 30: return FiltersCategoryMapId;
        case 8: case 25: return FiltersCategoryEdgeDetectionId;
        case 9: return FiltersCategoryEmbossId;
        case 0: case 1: case 12: case 13: case 14: case 15: case 16: case 17:
            return FiltersCategoryAdjustId;
        default: return FiltersCategoryOtherId;
        }
    }

    KisConfigWidget *createConfigurationWidget(QWidget *, const KisPaintDeviceSP, bool) const override
    {
        return nullptr; // 无 GUI 运行环境: 配置一律由 JNI 层直接组装 config
    }

    KisFilterConfigurationSP defaultConfiguration(KisResourcesInterfaceSP resourcesInterface) const override
    {
        KisFilterConfigurationSP config = factoryConfiguration(resourcesInterface);
        config->setProperty("reverieType", m_type);
        config->setProperty("p1", 0.0);
        config->setProperty("p2", 0.0);
        config->setProperty("p3", 0.0);
        config->setProperty("p4", 0.0);
        return config;
    }

    void processImpl(KisPaintDeviceSP device,
                     const QRect &applyRect,
                     const KisFilterConfigurationSP config,
                     KoUpdater *progressUpdater) const override
    {
        Q_UNUSED(progressUpdater);
        if (!device || !config || applyRect.isEmpty()) {
            return;
        }

        QVariant v;
        const int type = config->getProperty("reverieType", v) ? v.toInt() : m_type;
        const double p1 = config->getProperty("p1", v) ? v.toDouble() : 0.0;
        const double p2 = config->getProperty("p2", v) ? v.toDouble() : 0.0;
        const double p3 = config->getProperty("p3", v) ? v.toDouble() : 0.0;
        const double p4 = config->getProperty("p4", v) ? v.toDouble() : 0.0;

        // 外扩读入: 卷积类需要邻域像素; 边缘行为与全画布预览存在极小的边界差
        // (工作区被裁剪到脏区+margin), 属已接受的取舍。
        const int margin = reverieFilterMargin(type);
        const QRect work = applyRect.adjusted(-margin, -margin, margin, margin);

        // 与预览管线同款字节路径: convertToQImage(ARGB32_Premultiplied) → 内核 → writeBytes
        QImage img = device->convertToQImage(nullptr, work.x(), work.y(), work.width(), work.height());
        if (img.isNull()) {
            return;
        }

        const bool hasLut = config->getProperty("lut", v);
        const QByteArray lut = hasLut ? v.toByteArray() : QByteArray();
        if (type == 13 && lut.size() >= 768) {
            const quint8 *base = reinterpret_cast<const quint8 *>(lut.constData());
            reverieApplyCurvesLutKernel(img, base, base + 256, base + 512);
        } else if (type == 30 && lut.size() >= 1024) {
            qint32 gradientLut[256];
            memcpy(gradientLut, lut.constData(), sizeof(gradientLut));
            reverieApplyGradientMapKernel(img, gradientLut);
        } else {
            reverieApplyScalarKernel(img, type, p1, p2, p3, p4);
        }

        if (margin == 0) {
            device->writeBytes(img.constBits(),
                               applyRect.x(), applyRect.y(),
                               applyRect.width(), applyRect.height());
        } else {
            const int sx = applyRect.x() - work.x();
            const int sy = applyRect.y() - work.y();
            QImage out(applyRect.width(), applyRect.height(), img.format());
            for (int row = 0; row < out.height(); ++row) {
                memcpy(out.scanLine(row),
                       img.scanLine(sy + row) + sx * 4,
                       size_t(out.width()) * 4);
            }
            device->writeBytes(out.constBits(),
                               applyRect.x(), applyRect.y(),
                               out.width(), out.height());
        }
    }

    QRect neededRect(const QRect &rect, const KisFilterConfigurationSP, int lod) const override
    {
        Q_UNUSED(lod);
        const int margin = reverieFilterMargin(m_type);
        return rect.adjusted(-margin, -margin, margin, margin);
    }

    QRect changedRect(const QRect &rect, const KisFilterConfigurationSP, int lod) const override
    {
        Q_UNUSED(lod);
        return rect;
    }

private:
    int m_type;
};

} // namespace

void registerReverieRegistryFilters()
{
    static bool filtersDone = false;
    if (filtersDone) {
        return;
    }
    KisFilterRegistry *registry = KisFilterRegistry::instance();
    for (int type = 0; type <= 34; ++type) {
        const QString id = QStringLiteral("reverie-f%1").arg(type);
        if (!registry->get(id)) {
            registry->add(new ReverieKernelFilter(type));
        }
    }
    filtersDone = true;
}
