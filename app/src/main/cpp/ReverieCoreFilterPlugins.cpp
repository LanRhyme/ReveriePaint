/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCoreInternal.h"
#include <filter/kis_filter.h>
#include <filter/kis_filter_registry.h>
#include <filter/kis_filter_category_ids.h>
#include <filter/kis_filter_configuration.h>
#include <kis_convolution_kernel.h>
#include <kis_convolution_painter.h>
#include <kis_gaussian_kernel.h>
#include <kis_mask_generator.h>
#include <kis_lod_transform.h>
#include <KisSequentialIteratorProgress.h>
#include <KoConvolutionOp.h>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ============================================================================
// ReverieBlurFilter ("blur")
// ============================================================================
class ReverieBlurFilter : public KisFilter
{
public:
    ReverieBlurFilter() : KisFilter(id(), FiltersCategoryBlurId, i18n("&Blur..."))
    {
        setSupportsPainting(true);
        setSupportsAdjustmentLayers(true);
        setSupportsLevelOfDetail(true);
        setColorSpaceIndependence(FULLY_INDEPENDENT);
    }

    static inline KoID id() {
        return KoID("blur", i18n("Blur"));
    }

    KisConfigWidget *createConfigurationWidget(QWidget *, const KisPaintDeviceSP, bool) const override {
        return nullptr;
    }

    KisFilterConfigurationSP defaultConfiguration(KisResourcesInterfaceSP resourcesInterface) const override {
        KisFilterConfigurationSP config = factoryConfiguration(resourcesInterface);
        config->setProperty("lockAspect", true);
        config->setProperty("halfWidth", 5);
        config->setProperty("halfHeight", 5);
        config->setProperty("rotate", 0);
        config->setProperty("strength", 0);
        config->setProperty("shape", 0);
        return config;
    }

    void processImpl(KisPaintDeviceSP device,
                     const QRect &rect,
                     const KisFilterConfigurationSP config,
                     KoUpdater *progressUpdater) const override
    {
        if (!device || !config) return;
        QPoint srcTopLeft = rect.topLeft();
        KisLodTransformScalar t(device);
        QVariant value;
        const uint halfWidth = t.scale((config->getProperty("halfWidth", value)) ? value.toUInt() : 5);
        const uint halfHeight = t.scale((config->getProperty("halfHeight", value)) ? value.toUInt() : 5);
        int shape = (config->getProperty("shape", value)) ? value.toInt() : 0;
        uint width = 2 * halfWidth + 1;
        uint height = 2 * halfHeight + 1;
        qreal aspectRatio = (qreal)height / width;
        int rotate = (config->getProperty("rotate", value)) ? value.toInt() : 0;
        qreal strength = (config->getProperty("strength", value) ? value.toUInt() : 0) / (qreal)100;

        KisMaskGenerator *kas = nullptr;
        if (shape == 1) {
            kas = new KisRectangleMaskGenerator(width, aspectRatio, strength, strength, 2, true);
        } else {
            kas = new KisCircleMaskGenerator(width, aspectRatio, strength, strength, 2, true);
        }
        QBitArray channelFlags = config->channelFlags();
        if (channelFlags.isEmpty()) {
            channelFlags = QBitArray(device->colorSpace()->channelCount(), true);
        }
        KisConvolutionKernelSP kernel = KisConvolutionKernel::fromMaskGenerator(kas, rotate * M_PI / 180.0);
        delete kas;
        KisConvolutionPainter painter(device);
        painter.setChannelFlags(channelFlags);
        painter.setProgress(progressUpdater);
        painter.applyMatrix(kernel, device, srcTopLeft, srcTopLeft, rect.size(), BORDER_REPEAT);
    }

    QRect neededRect(const QRect &rect, const KisFilterConfigurationSP config, int lod) const override {
        KisLodTransformScalar t(lod);
        QVariant value;
        const int halfWidth = t.scale(config->getProperty("halfWidth", value) ? value.toUInt() : 5);
        const int halfHeight = t.scale(config->getProperty("halfHeight", value) ? value.toUInt() : 5);
        const int radius = qMax(halfWidth, halfHeight);
        return rect.adjusted(-radius, -radius, radius, radius);
    }

    QRect changedRect(const QRect &rect, const KisFilterConfigurationSP config, int lod) const override {
        return neededRect(rect, config, lod);
    }
};

// ============================================================================
// ReverieGaussianBlurFilter ("gaussian blur")
// ============================================================================
class ReverieGaussianBlurFilter : public KisFilter
{
public:
    ReverieGaussianBlurFilter() : KisFilter(id(), FiltersCategoryBlurId, i18n("&Gaussian Blur..."))
    {
        setSupportsPainting(true);
        setSupportsAdjustmentLayers(true);
        setSupportsLevelOfDetail(true);
        setColorSpaceIndependence(FULLY_INDEPENDENT);
    }

    static inline KoID id() {
        return KoID("gaussian blur", i18n("Gaussian Blur"));
    }

    KisConfigWidget *createConfigurationWidget(QWidget *, const KisPaintDeviceSP, bool) const override {
        return nullptr;
    }

    KisFilterConfigurationSP defaultConfiguration(KisResourcesInterfaceSP resourcesInterface) const override {
        KisFilterConfigurationSP config = factoryConfiguration(resourcesInterface);
        config->setProperty("horizRadius", 5);
        config->setProperty("vertRadius", 5);
        config->setProperty("lockAspect", true);
        return config;
    }

    void processImpl(KisPaintDeviceSP device,
                     const QRect &rect,
                     const KisFilterConfigurationSP config,
                     KoUpdater *progressUpdater) const override
    {
        if (!device || !config) return;
        KisLodTransformScalar t(device);
        const qreal horizontalRadius = t.scale(config->getDouble("horizRadius", 5));
        const qreal verticalRadius = t.scale(config->getDouble("vertRadius", 5));
        QBitArray channelFlags = config->channelFlags();
        if (channelFlags.isEmpty()) {
            channelFlags = QBitArray(device->colorSpace()->channelCount(), true);
        }
        KisGaussianKernel::applyGaussian(device, rect, horizontalRadius, verticalRadius, channelFlags, progressUpdater);
    }

    QRect neededRect(const QRect &rect, const KisFilterConfigurationSP config, int lod) const override {
        KisLodTransformScalar t(lod);
        QVariant value;
        const int halfWidth = config->getProperty("horizRadius", value)
            ? KisGaussianKernel::kernelSizeFromRadius(t.scale(value.toFloat())) / 2 : 5;
        const int halfHeight = config->getProperty("vertRadius", value)
            ? KisGaussianKernel::kernelSizeFromRadius(t.scale(value.toFloat())) / 2 : 5;
        return rect.adjusted(-halfWidth * 2, -halfHeight * 2, halfWidth * 2, halfHeight * 2);
    }

    QRect changedRect(const QRect &rect, const KisFilterConfigurationSP config, int lod) const override {
        KisLodTransformScalar t(lod);
        QVariant value;
        const int halfWidth = config->getProperty("horizRadius", value)
            ? KisGaussianKernel::kernelSizeFromRadius(t.scale(value.toFloat())) / 2 : 5;
        const int halfHeight = config->getProperty("vertRadius", value)
            ? KisGaussianKernel::kernelSizeFromRadius(t.scale(value.toFloat())) / 2 : 5;
        return rect.adjusted(-halfWidth, -halfHeight, halfWidth, halfHeight);
    }
};

// ============================================================================
// ReverieUnsharpFilter ("unsharp")
// ============================================================================
class ReverieUnsharpFilter : public KisFilter
{
public:
    ReverieUnsharpFilter() : KisFilter(id(), FiltersCategoryEnhanceId, i18n("&Unsharp Mask..."))
    {
        setSupportsPainting(true);
        setSupportsAdjustmentLayers(true);
        setSupportsThreading(true);
        setSupportsLevelOfDetail(false);
        setColorSpaceIndependence(FULLY_INDEPENDENT);
    }

    static inline KoID id() {
        return KoID("unsharp", i18n("Unsharp Mask"));
    }

    KisConfigWidget *createConfigurationWidget(QWidget *, const KisPaintDeviceSP, bool) const override {
        return nullptr;
    }

    KisFilterConfigurationSP defaultConfiguration(KisResourcesInterfaceSP resourcesInterface) const override {
        KisFilterConfigurationSP config = factoryConfiguration(resourcesInterface);
        config->setProperty("halfSize", 1);
        config->setProperty("amount", 0.5);
        config->setProperty("threshold", 0);
        config->setProperty("lightnessOnly", true);
        return config;
    }

    void processImpl(KisPaintDeviceSP device,
                     const QRect &rect,
                     const KisFilterConfigurationSP config,
                     KoUpdater *progressUpdater) const override
    {
        if (!device || !config) return;
        KisLodTransformScalar t(device);
        QVariant value;
        const qreal halfSize = t.scale(config->getProperty("halfSize", value) ? value.toDouble() : 1.0);
        const qreal amount = (config->getProperty("amount", value)) ? value.toDouble() : 0.5;
        const uint threshold = (config->getProperty("threshold", value)) ? value.toUInt() : 0;
        const uint lightnessOnly = (config->getProperty("lightnessOnly", value)) ? value.toBool() : true;

        QBitArray channelFlags = config->channelFlags();
        KisGaussianKernel::applyGaussian(device, rect, halfSize, halfSize, channelFlags, progressUpdater);

        qreal weights[2];
        qreal factor = 128;
        weights[0] = factor * (1. + amount);
        weights[1] = -factor * amount;

        const KoColorSpace *cs = device->colorSpace();
        const int pixelSize = cs->pixelSize();
        KoConvolutionOp *convolutionOp = cs->convolutionOp();
        if (!convolutionOp) return;

        quint8 *colors[2];
        colors[0] = new quint8[pixelSize];
        colors[1] = new quint8[pixelSize];

        KisSequentialIteratorProgress dstIt(device, rect, progressUpdater);
        while (dstIt.nextPixel()) {
            quint8 diff = 0;
            if (threshold == 1) {
                if (memcmp(dstIt.oldRawData(), dstIt.rawDataConst(), cs->pixelSize()) == 0) {
                    diff = 1;
                }
            } else {
                diff = cs->difference(dstIt.oldRawData(), dstIt.rawDataConst());
            }
            if (diff >= threshold) {
                memcpy(colors[0], dstIt.oldRawData(), pixelSize);
                memcpy(colors[1], dstIt.rawDataConst(), pixelSize);
                convolutionOp->convolveColors(colors, weights, dstIt.rawData(), factor, 0.0, 2, channelFlags);
            } else {
                memcpy(dstIt.rawData(), dstIt.oldRawData(), pixelSize);
            }
        }
        delete[] colors[0];
        delete[] colors[1];
    }

    QRect neededRect(const QRect &rect, const KisFilterConfigurationSP config, int lod) const override {
        KisLodTransformScalar t(lod);
        QVariant value;
        const int halfSize = config->getProperty("halfSize", value)
            ? KisGaussianKernel::kernelSizeFromRadius(t.scale(value.toDouble())) / 2 : 1;
        return rect.adjusted(-halfSize * 2, -halfSize * 2, halfSize * 2, halfSize * 2);
    }

    QRect changedRect(const QRect &rect, const KisFilterConfigurationSP config, int lod) const override {
        return neededRect(rect, config, lod);
    }
};

// ============================================================================
// Registration function for ReverieCore
// ============================================================================
void registerCoreFilters()
{
    static bool filtersDone = false;
    if (!filtersDone) {
        KisFilterRegistry *r = KisFilterRegistry::instance();
        if (!r->get("blur")) {
            r->add(new ReverieBlurFilter());
        }
        if (!r->get("gaussian blur")) {
            r->add(new ReverieGaussianBlurFilter());
        }
        if (!r->get("unsharp")) {
            r->add(new ReverieUnsharpFilter());
        }
        filtersDone = true;
    }
    // reverie-f0..f34 内核滤镜 (调整层/滤镜蒙版用), 独立幂等守卫
    registerReverieRegistryFilters();
}
