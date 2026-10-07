/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "ReverieCoreInternal.h"
#include "ReverieCoreColorSpaceHook.h"
#include <generator/kis_generator.h>
#include <generator/kis_generator_registry.h>
#include <generator/kis_generator_layer.h>
#include <filter/kis_filter_configuration.h>
#include <kis_processing_information.h>
#include <KisGlobalResourcesInterface.h>
#include <png.h>
#include <cmath>

namespace {
QImage decodePattern(const QByteArray &png)
{
    if (png.isEmpty() || png.size() > 16 * 1024 * 1024) return {};
    // Use the bundled PNG codec directly. Qt's headless image handler may not
    // expose a Size option; treating that as a malformed image rejects valid PNGs.
    png_image info{};
    info.version = PNG_IMAGE_VERSION;
    if (!png_image_begin_read_from_memory(&info, png.constData(), png.size())) {
        png_image_free(&info);
        return {};
    }
    if (info.width == 0 || info.height == 0 || info.width > 2048 || info.height > 2048) {
        png_image_free(&info);
        return {};
    }
    info.format = PNG_FORMAT_RGBA;
    QImage image(int(info.width), int(info.height), QImage::Format_RGBA8888);
    const bool decoded = !image.isNull() && png_image_finish_read(
        &info, nullptr, image.bits(), image.bytesPerLine(), nullptr);
    png_image_free(&info);
    return decoded ? image : QImage();
}

class PatternGenerator : public KisGenerator {
public:
    PatternGenerator()
        : KisGenerator(KoID("reverie-pattern", i18n("Pattern")),
                       KoID("reverie-fill", i18n("Reverie Fill")), i18n("Pattern Fill")) {}
    KisConfigWidget *createConfigurationWidget(QWidget *, const KisPaintDeviceSP, bool) const override
    {
        return nullptr;
    }
    void generate(KisProcessingInformation dst, const QSize &size,
                  const KisFilterConfigurationSP config, KoUpdater *) const override
    {
        auto device = dst.paintDevice();
        if (!device || !config || size.isEmpty()) return;
        QVariant value;
        if (!config->getProperty("pattern_image", value)) return;
        const QImage image = value.value<QImage>();
        if (image.isNull()) return;
        const QRect rect(dst.topLeft(), size);
        // Clear old generator pixels, including holes in transparent patterns.
        device->clear(rect);
        KisFillPainter painter(device);
        painter.fillRect(rect, KoPatternSP(new KoPattern(image, "Pattern", "pattern.png")));
    }
};

class FillConfigCommand : public KUndo2Command {
public:
    FillConfigCommand(KisGeneratorLayer *layer, KisFilterConfigurationSP before, KisFilterConfigurationSP after)
        : KUndo2Command(kundo2_i18n("Fill Layer")), m_layer(layer), m_before(before), m_after(after) {}
    void redo() override
    {
        // pushUndoCommand holds the image barrier; initial application precedes push.
        if (m_first) { m_first = false; return; }
        if (m_layer) m_layer->setFilter(m_after);
    }
    void undo() override { if (m_layer) m_layer->setFilter(m_before); }
private:
    QPointer<KisGeneratorLayer> m_layer;
    KisFilterConfigurationSP m_before, m_after;
    bool m_first = true;
};
}

KisFilterConfigurationSP ReverieCore::reverieMakePatternConfig(const QByteArray &png)
{
    const QImage image = decodePattern(png);
    if (image.isNull()) return nullptr;
    auto *registry = KisGeneratorRegistry::instance();
    if (!registry->get("reverie-pattern")) registry->add(KisGeneratorSP(new PatternGenerator()));
    auto generator = registry->get("reverie-pattern");
    auto config = generator->factoryConfiguration(KisGlobalResourcesInterface::instance());
    if (!config) return nullptr;
    config->setProperty("pattern_png", png);
    config->setProperty("pattern_image", image); // Decode once, not for every projection tile.
    return config;
}

bool ReverieCore::applyFillGeneratorConfig(int index, KisFilterConfigurationSP config)
{
    if (!m_document || !config || index < 0 || index >= m_layers.size()) return false;
    auto *layer = dynamic_cast<KisGeneratorLayer *>(m_layers[index].node);
    if (!layer) return false;
    const auto before = layer->filter();
    layer->setFilter(config);
    pushUndoCommand(new FillConfigCommand(layer, before, config));
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::setFillLayerPattern(int index, const QByteArray &png)
{
    if (!m_document || index < 0 || index >= m_layers.size()) return false;
    auto node = m_layers[index].node;
    if (!node || !node->isEditable(false)) {
        qWarning("Pattern: fill layer is not editable (%d)", index);
        return false;
    }
    if (dynamic_cast<KisGeneratorLayer *>(node)) {
        return applyFillGeneratorConfig(index, reverieMakePatternConfig(png));
    }
    auto *layer = dynamic_cast<KisPaintLayer *>(node);
    if (!layer || !node->property("reverie_is_fill").toBool()) {
        qWarning("Pattern: target is not a fill layer (%d)", index);
        return false;
    }
    const QImage image = decodePattern(png);
    if (image.isNull()) return false;
    auto device = layer->paintDevice();
    if (!device) return false;
    KisTransaction transaction(kundo2_i18n("Fill Layer Pattern"), device);
    device->clear();
    KisFillPainter painter(device);
    painter.fillRect(m_document->bounds(), KoPatternSP(new KoPattern(image, "Pattern", "pattern.png")));
    device->setDirty(m_document->bounds());
    pushUndoCommand(transaction.endAndTake());
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::floodFillPatternAt(int x, int y, int tolerance, bool sampleMerged,
                                    int expand, int feather, int closeGap, double opacity,
                                    const QString &compositeOp, const QByteArray &png)
{
    if (!m_document || !m_document->bounds().contains(x, y) || !std::isfinite(opacity)) return false;
    if (m_currentLayer < 0 || m_currentLayer >= m_layers.size()) return false;
    const auto node = m_layers[m_currentLayer].node;
    if (!node || !node->isEditable()) return false;
    auto target = currentPaintDevice();
    if (!target) return false;
    const QImage image = decodePattern(png);
    if (image.isNull()) return false;
    ensureRgbU8DifferenceHook();
    KisTransaction transaction(kundo2_i18n("Pattern Fill"), target);
    KisFillPainter painter(target);
    painter.setWidth(m_document->width());
    painter.setHeight(m_document->height());
    painter.setCareForSelection(true);
    painter.setUseCompositing(true);
    painter.setOpacitySpread(100);
    painter.setAntiAlias(true);
    painter.setFillThreshold(qBound(1, tolerance, 100));
    painter.setSizemod(qBound(-32, expand, 64));
    painter.setFeather(qBound(0, feather, 32));
    painter.setCloseGap(qBound(0, closeGap, 32));
    if (m_selection) painter.setSelection(m_selection);
    auto *layer = dynamic_cast<KisPaintLayer *>(node);
    if (layer && layer->alphaLocked()) painter.setChannelFlags(layer->channelLockFlags());
    painter.setOpacityF(qBound(0.0, opacity, 1.0));
    const QString effectiveOp = (compositeOp == "normal") ? COMPOSITE_OVER :
                                ((compositeOp == "difference") ? COMPOSITE_DIFF : compositeOp);
    painter.setCompositeOpId(effectiveOp);
    painter.setPattern(KoPatternSP(new KoPattern(image, "Pattern", "pattern.png")));
    painter.fillPattern(x, y, sampleMerged ? m_document->projection() : target);
    target->setDirty();
    pushUndoCommand(transaction.endAndTake());
    recompositeProjection();
    markDirty();
    return true;
}
