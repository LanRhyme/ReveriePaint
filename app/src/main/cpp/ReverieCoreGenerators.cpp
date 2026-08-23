/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * ReverieCoreGenerators.cpp - 纯色填充 generator (供 KisGeneratorLayer 非破坏填充层使用)
 *
 * 填充图层 v2: 用原生 KisGenerator + KisGeneratorLayer 替代"预填色颜料层"降级实现,
 * 换色即时生效 (setFilter 触发 generator 重算), 不再依赖 floodFill 补刀。
 */
#include "ReverieCoreInternal.h"

#include <generator/kis_generator.h>
#include <generator/kis_generator_registry.h>
#include <generator/kis_generator_layer.h>
#include <filter/kis_filter_configuration.h>
#include <kis_processing_information.h>
#include <kis_paint_device.h>
#include <KoColorSpace.h>
#include <KisGlobalResourcesInterface.h>
#include <QDebug>

namespace
{

class ReverieSolidColorGenerator : public KisGenerator
{
public:
    ReverieSolidColorGenerator()
        : KisGenerator(KoID("reverie-solid-color", i18n("Solid Color")),
                       KoID("reverie-fill", i18n("Reverie Fill")),
                       i18n("&Solid Color Fill..."))
    {
    }

    static inline KoID id()
    {
        return KoID("reverie-solid-color", i18n("Solid Color"));
    }

    KisConfigWidget *createConfigurationWidget(QWidget *, const KisPaintDeviceSP, bool) const override
    {
        return nullptr; // 无 GUI 环境: 配置组件一律 nullptr
    }

    KisFilterConfigurationSP defaultConfiguration(KisResourcesInterfaceSP resourcesInterface) const override
    {
        KisFilterConfigurationSP config = factoryConfiguration(resourcesInterface);
        if (config) {
            config->setProperty("color", QColor(255, 255, 255));
        }
        return config;
    }

    void generate(KisProcessingInformation dst,
                  const QSize &size,
                  const KisFilterConfigurationSP config,
                  KoUpdater *progressUpdater) const override
    {
        Q_UNUSED(progressUpdater);
        KisPaintDeviceSP dev = dst.paintDevice();
        // 插桩取证: 真机 SIGSEGV (fault 0x0) 前伴随空 profile 警告, 此处逐级标记定位
        if (!dev || !dev->colorSpace() || !config) {
            qWarning("reverie-solid-color: generate abort dev=%p cs=%p config=%p",
                     (void *)dev.data(),
                     dev ? (const void *)dev->colorSpace() : nullptr,
                     (void *)config.data());
            return;
        }
        QVariant v;
        QColor c = config->getProperty("color", v) ? v.value<QColor>() : QColor(Qt::white);
        if (!c.isValid()) {
            qWarning("reverie-solid-color: color prop invalid, fallback white");
            c = QColor(Qt::white);
        }
        const QRect rect(dst.topLeft(), size);
        dev->fill(rect, KoColor(c, dev->colorSpace()));
    }
};

} // namespace

void registerReverieGenerators()
{
    static bool generatorsDone = false;
    if (!generatorsDone) {
        KisGeneratorRegistry *r = KisGeneratorRegistry::instance();
        if (!r->get(QStringLiteral("reverie-solid-color"))) {
            r->add(KisGeneratorSP(new ReverieSolidColorGenerator()));
        }
        generatorsDone = true;
    }
}

KisFilterConfigurationSP ReverieCore::reverieMakeSolidColorConfig(quint32 rgba)
{
    registerReverieGenerators();
    KisGenerator *gen = dynamic_cast<KisGenerator *>(KisGeneratorRegistry::instance()->get("reverie-solid-color").data());
    if (!gen) {
        return nullptr;
    }
    KisFilterConfigurationSP cfg = gen->factoryConfiguration(KisGlobalResourcesInterface::instance());
    if (!cfg) {
        return nullptr;
    }
    cfg->setProperty("color", QColor::fromRgba(rgba));
    return cfg;
}

bool ReverieCore::setFillLayerColor(int index, quint32 colorArgb)
{
    if (index < 0 || index >= m_layers.size() || !m_document) {
        return false;
    }
    KisGeneratorLayer *gl = dynamic_cast<KisGeneratorLayer *>(m_layers[index].node);
    if (!gl) {
        return false;
    }
    KisFilterConfigurationSP cfg = reverieMakeSolidColorConfig(colorArgb);
    if (!cfg) {
        return false;
    }
    // setFilter 触发 generator 重算; 结构性刷新走全量重合成
    gl->setFilter(cfg);
    recompositeProjection();
    markDirty();
    return true;
}
