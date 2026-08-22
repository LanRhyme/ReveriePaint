/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreBrush.cpp - Brush engine: paintop registration, pressure response, brush lifecycle
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

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
    return m_presets.size();
}

int ReverieCore::loadBrushResources(const QString &dirPath)
{
    // The shared resources interface: presets resolve their brush_definition
    // filename through it, so the loaded brush files must live here. It is
    // created once and reused by every loadBrushPreset call.
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    QDir dir(dirPath);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.gbr") << QStringLiteral("*.gih")
                      << QStringLiteral("*.png") << QStringLiteral("*.svg"),
        QDir::Files, QDir::Name);
    int loaded = 0;
    for (const QString &base : files) {
        const QString fullPath = dir.filePath(base);
        KoResource *res = nullptr;
        if (base.endsWith(QLatin1String(".gbr"))) {
            res = new KisGbrBrush(base);
        } else if (base.endsWith(QLatin1String(".gih"))) {
            res = new KisImagePipeBrush(base);
        } else if (base.endsWith(QLatin1String(".png"))) {
            res = new KisPngBrush(base);
        } else if (base.endsWith(QLatin1String(".svg"))) {
            res = new KisSvgBrush(base);
        }
        if (!res) {
            continue;
        }
        QFile f(fullPath);
        if (f.open(QIODevice::ReadOnly)) {
            // The resource's filename() is the bare file name (matching the
            // filename attribute in presets' brush_definition), so we load
            // from the full path manually instead of KoResource::load().
            if (res->loadFromDevice(&f, m_brushResources)) {
                KisLocalStrokeResources *lr =
                    dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());
                if (lr) {
                    lr->addResource(KoResourceSP(res));
                    ++loaded;
                } else {
                    delete res;
                }
            } else {
                delete res;
            }
            f.close();
        } else {
            delete res;
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
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return false;
    }
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    const bool ok = preset->loadFromDevice(&f, m_brushResources);
    f.close();
    RPC_LOG("RPC loadBrushPreset idx=%d path=%s ok=%d", index, path.toUtf8().constData(), ok);
    if (!ok) {
        return false;
    }
    m_brushPreset = preset;
    m_brushPresetIndex = index;
    if (m_brushPreset && m_brushPreset->settings()) {
        const bool isEraserPreset = m_brushPreset->settings()->paintOpCompositeOp() == QLatin1String("erase") ||
                                    m_brushPreset->settings()->effectivePaintOpCompositeOp() == QLatin1String("erase");
        if (m_toolMode == ToolEraser || isEraserPreset) {
            m_brushPreset->settings()->setEraserMode(true);
        }
    }
    // Re-apply the user's current size / opacity / flow over the preset's
    // own values (they are stored per preset and would otherwise override)
    setBrushSize(m_brushSize);
    setBrushOpacity(m_brushOpacity);
    setBrushFlow(m_brushFlow);
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

QVector<double> ReverieCore::brushPresetDefaults(int index)
{
    if (index < 0 || index >= m_presets.size()) {
        return {20.0, 1.0, 1.0};
    }
    registerPaintOps();
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    QFile f(m_presets[index].second);
    if (!f.open(QIODevice::ReadOnly)) {
        return {20.0, 1.0, 1.0};
    }
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    const bool ok = preset->loadFromDevice(&f, m_brushResources);
    f.close();
    if (!ok) {
        return {20.0, 1.0, 1.0};
    }
    double size = 20.0;
    if (preset->settings()) {
        size = preset->settings()->paintOpSize();
        if (!(size > 0.0) || size != size) {  // NaN / non-positive guard
            size = 20.0;
        }
    }
    const double opacity = preset->settings()->getDouble("OpacityValue", 1.0);
    const double flow = preset->settings()->getDouble("FlowValue", 1.0);
    return {size, opacity, flow};
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

void ReverieCore::setBrushSpacing(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        KisBrushBasedPaintOpSettings *bs =
            dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
        if (bs) {
            bs->setSpacing(v);
        }
    }
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
        m_brushPreset->settings()->setPaintOpScatter(v);
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
        const bool isEraserPreset = m_brushPreset->settings()->paintOpCompositeOp() == QLatin1String("erase");
        m_brushPreset->settings()->setEraserMode(m_toolMode == ToolEraser || isEraserPreset);
    }
}

void ReverieCore::setBrushCompositeOp(const QString &op)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpCompositeOp(op);
        if (m_toolMode == ToolEraser || op == QLatin1String("erase")) {
            m_brushPreset->settings()->setEraserMode(true);
        } else {
            m_brushPreset->settings()->setEraserMode(false);
        }
    }
}

