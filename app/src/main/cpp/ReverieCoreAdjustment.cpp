/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * ReverieCoreAdjustment.cpp - 非破坏性调整图层 (真 KisAdjustmentLayer)
 *
 * 创建/配置编辑/配置回读。滤镜 ID 约定 reverie-f<type> (见 ReverieCoreFilterRegistry.cpp),
 * 配置属性 reverieType/p1..p4/lut(QByteArray, 仅曲线 LUT 与渐变映射使用)。
 */
#include "ReverieCoreInternal.h"

#include <filter/kis_filter.h>
#include <filter/kis_filter_registry.h>
#include <filter/kis_filter_configuration.h>
#include <KisResourcesInterface.h>
#include <KisGlobalResourcesInterface.h>
#include <kis_adjustment_layer.h>
#include <kis_image.h>
#include <QJsonDocument>
#include <QJsonObject>

KisFilterConfigurationSP ReverieCore::reverieMakeConfig(int filterType, double p1, double p2, double p3, double p4,
                                           const QByteArray &lut)
{
    KisFilterSP filter = KisFilterRegistry::instance()->get(QString("reverie-f%1").arg(filterType));
    if (!filter) {
        return nullptr;
    }
    KisFilterConfigurationSP config = filter->factoryConfiguration(KisGlobalResourcesInterface::instance());
    config->setProperty("reverieType", filterType);
    config->setProperty("p1", p1);
    config->setProperty("p2", p2);
    config->setProperty("p3", p3);
    config->setProperty("p4", p4);
    if (!lut.isEmpty()) {
        config->setProperty("lut", lut);
    }
    return config;
}

bool ReverieCore::createAdjustmentLayer(const QString &name, int filterType,
                                        double p1, double p2, double p3, double p4)
{
    KisImageSP image = m_document;
    if (!image) {
        return false;
    }
    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }
    const int count = m_layers.size();
    QString finalName = name;
    if (finalName.isEmpty()) {
        finalName = QString("调整图层 %1").arg(count);
    }
    KisFilterConfigurationSP config = reverieMakeConfig(filterType, p1, p2, p3, p4, QByteArray());
    if (!config) {
        return false;
    }

    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);

    KisAdjustmentLayerSP node = new KisAdjustmentLayer(image, finalName, config, nullptr);
    pushUndoCommand(new KisImageLayerAddCommand(image, node, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(node.data());
    if (idx >= 0) {
        m_currentLayer = idx;
    }
    markDirty();
    return true;
}

namespace {
// 调整层配置变更的撤销命令: undo/redo 各自恢复旧/新配置并刷新投影。
class ReverieAdjustmentConfigCommand : public KUndo2Command
{
public:
    ReverieAdjustmentConfigCommand(KisImageWSP image, KisAdjustmentLayer *layer,
                                   KisFilterConfigurationSP oldCfg, KisFilterConfigurationSP newCfg)
        : KUndo2Command(kundo2_i18n("调整层配置"))
        , m_image(image)
        , m_layer(layer)
        , m_old(oldCfg)
        , m_new(newCfg)
    {
    }

    void redo() override
    {
        if (m_layer && m_new) {
            m_layer->setFilter(m_new);
            refresh();
        }
    }

    void undo() override
    {
        if (m_layer && m_old) {
            m_layer->setFilter(m_old);
            refresh();
        }
    }

private:
    void refresh()
    {
        if (m_image) {
            m_image->refreshGraphAsync();
            m_image->waitForDone();
        }
    }

    KisImageWSP m_image;
    QPointer<KisAdjustmentLayer> m_layer;
    KisFilterConfigurationSP m_old;
    KisFilterConfigurationSP m_new;
};
} // namespace

bool ReverieCore::setAdjustmentLayerConfig(int index, int filterType,
                                           double p1, double p2, double p3, double p4,
                                           const QByteArray &lut)
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    auto *layer = dynamic_cast<KisAdjustmentLayer *>(m_layers[index].node);
    if (!layer) {
        return false;
    }
    KisFilterConfigurationSP newCfg = reverieMakeConfig(filterType, p1, p2, p3, p4, lut);
    if (!newCfg) {
        return false;
    }
    KisFilterConfigurationSP oldCfg = layer->filter();

    // 与旧配置完全一致则跳过 (避免空撤销条目)
    if (oldCfg && oldCfg->getProperty("reverieType").toInt() == filterType
        && qFuzzyCompare(oldCfg->getProperty("p1").toDouble(), p1)
        && qFuzzyCompare(oldCfg->getProperty("p2").toDouble(), p2)
        && qFuzzyCompare(oldCfg->getProperty("p3").toDouble(), p3)
        && qFuzzyCompare(oldCfg->getProperty("p4").toDouble(), p4)
        && oldCfg->getProperty("lut").toByteArray() == lut) {
        return true;
    }

    pushUndoCommand(new ReverieAdjustmentConfigCommand(m_document, layer, oldCfg, newCfg));
    syncLayersFromImage();
    markDirty();
    return true;
}

QString ReverieCore::getAdjustmentLayerConfig(int index)
{
    if (index < 0 || index >= m_layers.size()) {
        return QString();
    }
    auto *layer = dynamic_cast<KisAdjustmentLayer *>(m_layers[index].node);
    if (!layer || !layer->filter()) {
        return QString();
    }
    const KisFilterConfigurationSP cfg = layer->filter();
    QJsonObject obj;
    obj["type"] = cfg->getProperty("reverieType").toInt();
    obj["p1"] = cfg->getProperty("p1").toDouble();
    obj["p2"] = cfg->getProperty("p2").toDouble();
    obj["p3"] = cfg->getProperty("p3").toDouble();
    obj["p4"] = cfg->getProperty("p4").toDouble();
    const QByteArray lut = cfg->getProperty("lut").toByteArray();
    if (!lut.isEmpty()) {
        obj["lut"] = QString::fromLatin1(lut.toBase64());
    }
    return QString::fromUtf8(QJsonDocument(obj).toJson(QJsonDocument::Compact));
}
