/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import kotlin.math.abs

/** 控件生效方式（执行文档 W-03 / W-04） */
enum class ControlClass {
    /** 有直接 setter，不需要重载 preset（applier 在 loadPreset 后补调） */
    LIVE,

    /** 写进有效 kpp 后重载才生效 */
    REBUILD,

    /** 不进 kpp，只由 VM / 工具层消费 */
    APP_ONLY,
}

/**
 * 工作台控件。[key] 是 overrides.json 里的稳定键名，**不可改名**（改名等于丢用户数据）。
 * [group] 非空的控件按组写入（masking 组整体从有效 BrushParams 写，保持旧写入器第 17 节的行为）。
 */
enum class ControlId(val key: String, val klass: ControlClass, val group: String? = null, val curveOption: String? = null) {
    // ---- 实时（LIVE）----
    SIZE("size", ControlClass.LIVE),
    OPACITY("opacity", ControlClass.LIVE),
    FLOW("flow", ControlClass.LIVE),
    ANGLE("angle", ControlClass.LIVE),
    ROTATION("rotation", ControlClass.LIVE),

    // ---- 需要重建（REBUILD）。声明顺序 = 写入顺序：先换引擎/笔尖，再写曲线，最后写数值类 ----
    PAINTOP_ID("paintOpId", ControlClass.REBUILD),
    TIP_ASSET("tipAsset", ControlClass.REBUILD),
    CURVE_SIZE("curve.Size", ControlClass.REBUILD, curveOption = "Size"),
    CURVE_OPACITY("curve.Opacity", ControlClass.REBUILD, curveOption = "Opacity"),
    CURVE_FLOW("curve.Flow", ControlClass.REBUILD, curveOption = "Flow"),
    CURVE_ROTATION("curve.Rotation", ControlClass.REBUILD, curveOption = "Rotation"),
    CURVE_SCATTER("curve.Scatter", ControlClass.REBUILD, curveOption = "Scatter"),
    CURVE_RATIO("curve.Ratio", ControlClass.REBUILD, curveOption = "Ratio"),
    CURVE_SOFTNESS("curve.Softness", ControlClass.REBUILD, curveOption = "Softness"),
    CURVE_SPACING("curve.Spacing", ControlClass.REBUILD, curveOption = "Spacing"),
    CURVE_SMUDGE_RATE("curve.SmudgeRate", ControlClass.REBUILD, curveOption = "SmudgeRate"),
    CURVE_COLOR_RATE("curve.ColorRate", ControlClass.REBUILD, curveOption = "ColorRate"),
    SPACING("spacing", ControlClass.REBUILD),
    SCATTER("scatter", ControlClass.REBUILD),
    SCATTER_BOTH_AXES("scatterBothAxes", ControlClass.REBUILD),
    SOFTNESS("softness", ControlClass.REBUILD),
    SHARPNESS("sharpness", ControlClass.REBUILD),
    RATIO("ratio", ControlClass.REBUILD),
    FADE("fade", ControlClass.REBUILD),
    TIP_SHAPE("tipShape", ControlClass.REBUILD),
    SPIKES("spikes", ControlClass.REBUILD),
    ANTI_ALIASING("antiAliasing", ControlClass.REBUILD),
    COMPOSITE_OP("compositeOp", ControlClass.REBUILD),
    RANDOM_FLIP_X("randomFlipX", ControlClass.REBUILD),
    RANDOM_FLIP_Y("randomFlipY", ControlClass.REBUILD),
    FOLLOW_DIRECTION("followDirection", ControlClass.REBUILD),
    JITTER_ANGLE("jitterAngle", ControlClass.REBUILD),
    JITTER_SIZE("jitterSize", ControlClass.REBUILD),
    TEXTURE_ENABLED("textureEnabled", ControlClass.REBUILD),
    TEXTURE_SCALE("textureScale", ControlClass.REBUILD),
    TEXTURE_STRENGTH("textureStrength", ControlClass.REBUILD),
    TEXTURE_STRENGTH_PRESSURE("textureStrengthPressure", ControlClass.REBUILD),
    TEXTURE_MODE("textureMode", ControlClass.REBUILD),
    TEXTURE_INVERT("textureInvert", ControlClass.REBUILD),
    TEXTURE_SOFT("textureSoft", ControlClass.REBUILD),
    TEXTURE_BRIGHTNESS("textureBrightness", ControlClass.REBUILD),
    TEXTURE_CONTRAST("textureContrast", ControlClass.REBUILD),
    TEXTURE_PATTERN("texturePattern", ControlClass.REBUILD),
    HUE_JITTER("hueJitter", ControlClass.REBUILD),
    SAT_JITTER("satJitter", ControlClass.REBUILD),
    VAL_JITTER("valJitter", ControlClass.REBUILD),
    MIX("secondaryMix", ControlClass.REBUILD),
    MIX_PRESSURE("pressureColorMix", ControlClass.REBUILD),
    COLOR_RATE("colorRate", ControlClass.REBUILD),
    SMUDGE_LENGTH("smudgeLength", ControlClass.REBUILD),
    SMUDGE_MODE("smudgeMode", ControlClass.REBUILD),
    AIRBRUSH("airbrush", ControlClass.REBUILD),
    AIRBRUSH_RATE("airbrushRate", ControlClass.REBUILD),
    MASKING_ENABLED("maskingEnabled", ControlClass.REBUILD, group = "masking"),
    MASKING_COMPOSITE_OP("maskingCompositeOp", ControlClass.REBUILD, group = "masking"),
    MASKING_SIZE_RATIO("maskingSizeRatio", ControlClass.REBUILD, group = "masking"),
    MASKING_SPACING("maskingSpacing", ControlClass.REBUILD, group = "masking"),
    MASKING_TIP_ASSET("maskingTipAsset", ControlClass.REBUILD, group = "masking"),
    MASKING_TIP_SHAPE("maskingTipShape", ControlClass.REBUILD, group = "masking"),
    MASKING_FADE("maskingFade", ControlClass.REBUILD, group = "masking"),
    MASKING_SCATTER("maskingScatter", ControlClass.REBUILD, group = "masking"),
    MASKING_SCATTER_BOTH_AXES("maskingScatterBothAxes", ControlClass.REBUILD, group = "masking"),
    MASKING_ANGLE("maskingAngle", ControlClass.REBUILD, group = "masking"),
    MASKING_FLIP_X("maskingFlipX", ControlClass.REBUILD, group = "masking"),
    MASKING_FLIP_Y("maskingFlipY", ControlClass.REBUILD, group = "masking"),

    // ---- 只在 App 层（APP_ONLY）----
    MASKING_SOFTNESS("maskingSoftness", ControlClass.APP_ONLY),
    STREAMLINE("streamline", ControlClass.APP_ONLY),
    TAPER("taper", ControlClass.APP_ONLY),
    SPEED_SIZE("speedSize", ControlClass.APP_ONLY),
    MIN_SIZE_LIMIT("minSizeLimit", ControlClass.APP_ONLY),
    MAX_SIZE_LIMIT("maxSizeLimit", ControlClass.APP_ONLY),
    PRESSURE_ENABLED("pressureEnabled", ControlClass.APP_ONLY),
    PRESSURE_CURVE("pressureCurve", ControlClass.APP_ONLY),
    AUTHOR("author", ControlClass.APP_ONLY),
    AUTHOR_LOCKED("isAuthorLocked", ControlClass.APP_ONLY),
    DESCRIPTION("description", ControlClass.APP_ONLY),
    VERSION("version", ControlClass.APP_ONLY),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): ControlId? = byKey[key]
    }
}

/** 适用性判断所需的预设能力（W-16） */
data class PresetContext(
    val paintOpId: String,
    val hasBrushDef: Boolean,
    val hasMaskGenerator: Boolean,
    val isMyPaint: Boolean,
) {
    val isColorSmudge: Boolean get() = paintOpId == "colorsmudge"

    companion object {
        /** .myb（MyPaint）没有 Krita XML：只有 LIVE / APP_ONLY 控件适用 */
        val MYPAINT = PresetContext(paintOpId = "mypaintbrush", hasBrushDef = false, hasMaskGenerator = false, isMyPaint = true)

        fun of(xml: String): PresetContext = PresetContext(
            paintOpId = KppXml.paintOpId(xml).orEmpty(),
            hasBrushDef = KppXml.mainBrushDef(xml) != null,
            hasMaskGenerator = KppXml.hasMaskGenerator(xml),
            isMyPaint = false,
        )
    }
}

/**
 * 单个控件的读、写、适用性与 BrushParams 映射。
 * - [read] 按 Krita 读取规则从 XML 复原值；返回 null 表示该预设里没有可读的值（保持基线）。
 * - [write] 只写本控件拥有的键（无串写）；[eff] 是叠加了全部覆盖后的有效参数，仅供需要上下文的控件使用。
 * - [normalize] 用于比较（例如 AA 的 0..3 档在 kpp 里只有开/关）。
 */
class ControlSpec(
    val id: ControlId,
    val get: (BrushParams) -> Any,
    val set: (BrushParams, Any) -> BrushParams,
    val read: ((String) -> Any?)? = null,
    val write: ((String, Any, BrushParams) -> String)? = null,
    val applicable: (PresetContext) -> Boolean = { !it.isMyPaint },
    val normalize: (Any) -> Any = { it },
)

/**
 * 声明式的"工作台控件 ↔ Krita kpp 键"映射表（执行文档 W-03）。
 *
 * 读写规则的 Krita 依据见 [KppSensors] 类注释；与执行文档 W-03 的差异（实现时以代码证据为准）：
 * - SPACING 写 brush_definition 的 `spacing`，`useAutoSpacing="1"` 时写 `autoSpacingCoeff`；
 *   **不写** `Spacing` / `SpacingValue`（268 个内置资产里 `Spacing` param 出现 0 次，SpacingValue 是乘数）。
 * - ANGLE 只存基础角（brush_definition `angle`，弧度）；ROTATION 存 `ReverieRotation`（引擎不读，
 *   由 applier 用 setBrushAngle(angle+rotation) 下发）。
 * - CURVE_* 关闭时只写 `<Id>UseCurve=false`，不取消勾选：Scatter / Softness / ColorRate 等选项的
 *   数值另有滑块，取消勾选会让数值滑块一起失效。曲线本身照写，重新启用时还在。
 * - masking 组沿用旧写入器第 17 节（键名待 KisMaskingBrushOption 证据，NEEDS_EVIDENCE）。
 */
object PresetPropertyMap {
    const val TOL = 1e-6

    private fun d(v: Any): Double = (v as Number).toDouble()
    private fun i(v: Any): Int = (v as Number).toDouble().let { Math.round(it).toInt() }
    private fun b(v: Any): Boolean = v as Boolean
    private fun s(v: Any): String = v as String
    private fun f(v: Double): String = KppXml.fmt(v)

    // ---------------------------------------------------------------- 曲线选项

    /** 曲线强度可由 `<Id>Value` 表达的选项；其余选项的 Value 由数值滑块拥有（或是主属性），强度恒为 1 */
    private val CURVE_OWNS_VALUE = setOf("Size")

    fun readCurve(xml: String, option: String): CurveValue? {
        val sensor = KppSensors.sensorXml(xml, option)
        val checked = KppSensors.isChecked(xml, option)
        val hasExplicitCheck = KppXml.getBool(xml, KppSensors.pressureKey(option), false)
        if (sensor == null && !KppXml.has(xml, "${option}UseCurve") && !hasExplicitCheck) return null
        val ids = KppSensors.sensorIds(sensor)
        val primary = primarySensor(option, ids)
        val id = primary ?: defaultSensorFor(option)
        val curve = KppSensors.commonCurve(xml, option) ?: KppSensors.sensorCurve(sensor, id) ?: KppSensors.LINEAR
        // 只有抖动用的 fuzzy（归 jitterSize/jitterAngle 管）时，曲线本身视为未启用
        val enabled = checked && KppSensors.useCurve(xml, option) && !(ids.isNotEmpty() && primary == null)
        val strengthFromAttr = Regex("""\bstrength="([^"]*)"""").find(sensor ?: "")?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val strength = strengthFromAttr ?: if (option in CURVE_OWNS_VALUE) (KppXml.getDouble(xml, "${option}Value") ?: 1.0) else 1.0
        return CurveValue(enabled, id, CurveValue.normalizeCurve(curve), strength)
    }

    /** 抖动以 fuzzy 子传感器叠加在这些选项上（见 [mergedJitterSpec]），fuzzy 不算曲线控件的传感器 */
    private val MERGED_JITTER_OPTIONS = setOf("Size", "Rotation")

    /** 曲线控件代表的传感器：第一个子传感器（Size/Rotation 跳过抖动用的 fuzzy） */
    fun primarySensor(option: String, ids: List<String>): String? =
        ids.firstOrNull { !(option in MERGED_JITTER_OPTIONS && it == "fuzzy") }

    fun defaultSensorFor(option: String): String = when (option) {
        "Rotation" -> "drawingangle"
        "Scatter" -> "fuzzy"
        else -> "pressure"
    }

    private fun drawingAngleSensor(curve: String) =
        """<!DOCTYPE params><params fanCornersStep="30" fanCornersEnabled="0" angleOffset="0" id="drawingangle"><curve>$curve</curve></params>"""

    private fun withSensorStrength(sensorXml: String, strength: Double): String {
        val fmt = CurveValue.fmt(strength)
        val paramsRegex = Regex("""<params\b([^>]*)>""")
        val m = paramsRegex.find(sensorXml) ?: return sensorXml
        val attrs = m.groupValues[1].replace(Regex("""\s*\bstrength="[^"]*""""), "")
        return sensorXml.replaceRange(m.range, "<params$attrs strength=\"$fmt\">")
    }

    fun writeCurve(xml: String, option: String, v: CurveValue): String {
        var x = xml
        val curve = CurveValue.normalizeCurve(v.curve)
        val old = KppSensors.sensorXml(x, option)
        val ids = KppSensors.sensorIds(old)
        val primary0 = primarySensor(option, ids)
        val jitterOpt = option in MERGED_JITTER_OPTIONS && "fuzzy" in ids
        val activeBefore = curveActive(xml, option)
        if (!v.enabled) {
            // 曲线本来就不存在（只有抖动用的 fuzzy）：原样关闭是恒等。
            // 改关闭状态曲线的形状无法与生效的抖动共存（Krita 同一选项共用 UseCurve），走下面的通用路径（抖动随之关闭，属耦合）。
            if (ids.isNotEmpty() && primary0 == null) {
                val cur = readCurve(xml, option)
                if (cur != null && !cur.enabled && cur.sensorId == v.sensorId && CurveValue.normalizeCurve(cur.curve) == curve) return xml
            }
            // 其余关闭照旧写 UseCurve=false：Krita 同一选项的抖动（fuzzy）随之关闭，属格式层耦合
        }
        fun fresh() = if (v.sensorId == "drawingangle") drawingAngleSensor(curve) else KppSensors.single(v.sensorId, curve)
        val sensor = when {
            old == null || ids.isEmpty() -> fresh()
            // 已有该传感器：只改它的曲线，并把它排到最前，回读时曲线控件就代表它
            v.sensorId in ids -> KppSensors.setSensorCurve(old, v.sensorId, curve)?.let { KppSensors.moveFirst(it, v.sensorId) } ?: fresh()
            else -> {
                // 换传感器：替换掉原主传感器，保留其余（尤其是抖动用的 fuzzy）
                val primary = primarySensor(option, ids)
                val base = if (primary != null) KppSensors.removeSensor(old, primary) else old
                if (base == null) fresh() else KppSensors.moveFirst(KppSensors.addSensor(base, v.sensorId, curve), v.sensorId)
            }
        }
        // 选项原本未生效时 fuzzy 是休眠的：启用曲线不应顺带唤醒抖动
        val finalSensor = if (v.enabled && jitterOpt && !activeBefore && v.sensorId != "fuzzy") {
            KppSensors.removeSensor(sensor, "fuzzy") ?: sensor
        } else {
            sensor
        }
        val sensorWithStrength = if (v.enabled) withSensorStrength(finalSensor, v.strength) else finalSensor
        x = KppSensors.writeSensor(x, option, sensorWithStrength)
        // 编辑的未必是第一个子传感器：commonCurve 显式同步为本次写入的曲线
        if (KppSensors.commonCurve(x, option) != null) x = KppXml.set(x, "${option}commonCurve", curve)
        if (v.enabled) {
            x = KppXml.set(x, KppSensors.pressureKey(option), "true")
            x = KppXml.set(x, "${option}UseCurve", "true")
            if (option in CURVE_OWNS_VALUE) x = KppXml.set(x, "${option}Value", f(v.strength))
        } else {
            x = KppXml.set(x, "${option}UseCurve", "false")
        }
        return x
    }

    private fun curveSpec(id: ControlId): ControlSpec {
        val opt = id.curveOption!!
        return ControlSpec(
            id,
            get = { p -> p.dynamicOptions[opt] ?: "" },
            set = { p, v -> p.copy(dynamicOptions = p.dynamicOptions + (opt to s(v))) },
            read = { xml -> readCurve(xml, opt)?.encode() },
            write = { xml, v, _ -> CurveValue.parse(s(v))?.let { writeCurve(xml, opt, it) } ?: xml },
            applicable = { ctx ->
                !ctx.isMyPaint && when (opt) {
                    "SmudgeRate", "ColorRate" -> ctx.isColorSmudge
                    "Spacing" -> ctx.hasBrushDef
                    else -> true
                }
            },
            normalize = { v ->
                val c = CurveValue.parse(v as? String)
                when {
                    c == null -> v
                    c.enabled -> c.encode()
                    else -> c.copy(strength = 1.0).encode()
                }
            },
        )
    }

    // ---------------------------------------------------------------- 单值曲线选项（强度）

    /**
     * 选项已勾选且曲线生效时，数值回到"中性值"也不取消勾选：此时曲线仍在调制该属性，
     * 取消勾选会连带关掉用户的曲线（也会让恒等写产生副作用）。
     */
    fun curveActive(xml: String, option: String): Boolean =
        KppSensors.isChecked(xml, option) && KppSensors.useCurve(xml, option)

    private fun strengthSpec(
        id: ControlId,
        option: String,
        unchecked: Double,
        get: (BrushParams) -> Double,
        set: (BrushParams, Double) -> BrushParams,
        enable: (Double) -> Boolean,
        applicable: (PresetContext) -> Boolean = { !it.isMyPaint },
    ) = ControlSpec(
        id,
        get = { get(it) },
        set = { p, v -> set(p, d(v)) },
        read = { xml -> KppSensors.readStrength(xml, option, unchecked) },
        write = { xml, v, _ -> KppSensors.writeStrength(xml, option, d(v), enable(d(v)) || curveActive(xml, option)) },
        applicable = applicable,
    )

    private fun jitterSpec(id: ControlId, option: String, get: (BrushParams) -> Double, set: (BrushParams, Double) -> BrushParams) =
        ControlSpec(
            id,
            get = { get(it) },
            set = { p, v -> set(p, d(v)) },
            read = { xml -> KppSensors.readStrength(xml, option, 0.0) },
            write = { xml, v, _ -> KppSensors.writeJitter(xml, option, d(v), d(v) > TOL) },
        )

    /** 抖动叠加在已有选项上（Rotation / Size）：fuzzy 在传感器里才算抖动，值取 `<Id>Value` */
    private fun mergedJitterSpec(id: ControlId, option: String, get: (BrushParams) -> Double, set: (BrushParams, Double) -> BrushParams) =
        ControlSpec(
            id,
            get = { get(it) },
            set = { p, v -> set(p, d(v)) },
            read = { xml ->
                val on = KppSensors.isChecked(xml, option) && KppSensors.useCurve(xml, option) &&
                    "fuzzy" in KppSensors.sensorIds(KppSensors.sensorXml(xml, option))
                if (on) KppXml.getDouble(xml, "${option}Value") ?: 1.0 else 0.0
            },
            write = { xml, v, _ ->
                val value = d(v)
                if (value > TOL) {
                    var x = KppXml.set(xml, KppSensors.pressureKey(option), "true")
                    x = KppXml.set(x, "${option}UseCurve", "true")
                    x = KppXml.set(x, "${option}Value", f(value))
                    val s0 = KppSensors.sensorXml(xml, option)
                    val merged = if (KppSensors.isChecked(xml, option)) KppSensors.addSensor(s0, "fuzzy") else KppSensors.single("fuzzy")
                    KppSensors.writeSensor(x, option, merged)
                } else {
                    val s0 = KppSensors.sensorXml(xml, option)
                    val effective = KppSensors.isChecked(xml, option) && KppSensors.useCurve(xml, option)
                    if (!effective || "fuzzy" !in KppSensors.sensorIds(s0)) {
                        xml
                    } else {
                        val rest = KppSensors.removeSensor(s0, "fuzzy")
                        if (rest == null) KppXml.set(xml, KppSensors.pressureKey(option), "false")
                        else KppSensors.writeSensor(xml, option, rest)
                    }
                }
            },
        )

    // ---------------------------------------------------------------- 笔尖定义

    private fun maskDef(xml: String): String? = KppXml.get(xml, KppXml.MASK_BRUSH_DEF)

    private fun maskDefAttr(xml: String, tag: String, attr: String): String? = maskDef(xml)?.let { KppXml.tagAttr(it, tag, attr) }

    private fun isAutoBrush(xml: String): Boolean = KppXml.brushAttr(xml, "type") == "auto_brush"

    fun readSpacing(xml: String): Double? {
        if (KppXml.mainBrushDef(xml) == null) return null
        val auto = KppXml.brushAttr(xml, "useAutoSpacing")?.trim()
        return if (auto == "1" || auto.equals("true", true)) {
            KppXml.brushAttr(xml, "autoSpacingCoeff")?.toDoubleOrNull()
        } else {
            KppXml.brushAttr(xml, "spacing")?.toDoubleOrNull()
        }
    }

    fun writeSpacing(xml: String, v: Double): String {
        val auto = KppXml.brushAttr(xml, "useAutoSpacing")?.trim()
        return if (auto == "1" || auto.equals("true", true)) KppXml.setBrushAttr(xml, "autoSpacingCoeff", f(v))
        else KppXml.setBrushAttr(xml, "spacing", f(v))
    }

    /** 引擎对该预设报告的笔刷大小：paintopSize，缺省退回主 MaskGenerator 的 diameter（未乘 scale）。 */
    fun readPaintOpSize(xml: String): Double? =
        KppXml.get(xml, "paintopSize")?.toDoubleOrNull() ?: KppXml.maskGenAttr(xml, "diameter")?.toDoubleOrNull()

    fun readSize(xml: String): Double? {
        if (!isAutoBrush(xml)) return null
        val dia = KppXml.maskGenAttr(xml, "diameter")?.toDoubleOrNull() ?: return null
        val scale = KppXml.brushAttr(xml, "scale")?.toDoubleOrNull() ?: 1.0
        return dia * scale
    }

    private fun tipType(file: String): String = when (file.substringAfterLast('.').lowercase()) {
        "gbr" -> "gbr_brush"
        "gih" -> "image_pipe_brush"
        "svg" -> "svg_brush"
        else -> "png_brush"
    }

    fun readTipAsset(xml: String): String {
        val type = KppXml.brushAttr(xml, "type") ?: return ""
        if (type == "auto_brush") return ""
        return KppXml.brushAttr(xml, "filename").orEmpty()
    }

    /** 换成基于文件的笔尖；若 file 为空则恢复为自动笔尖；scale 沿用原定义（ABR 导入写入的 scale 决定报告尺寸，见 AbrSnapshotBuilder.formatScale） */
    fun writeTipAsset(xml: String, file: String, eff: BrushParams): String {
        if (readTipAsset(xml) == file) return xml
        if (file.isBlank()) {
            val def = KppXml.autoBrushDef(
                size = eff.size,
                spacing = eff.spacing,
                angleRad = Math.toRadians(eff.angle),
                fade = eff.fade,
                shape = eff.tipShape,
                spikes = eff.spikes,
                ratio = eff.ratio,
                antiAliasing = eff.antiAliasing,
            )
            var x = KppXml.setMainBrushDef(xml, def)
            x = KppXml.remove(x, "requiredBrushFile")
            x = KppXml.remove(x, "requiredBrushFilesList")
            return x
        }
        val scale = KppXml.brushAttr(xml, "scale")?.takeIf { it.isNotBlank() && !isAutoBrush(xml) } ?: "1"
        val rad = Math.toRadians(eff.angle)
        val def = """<Brush scale="$scale" type="${tipType(file)}" useAutoSpacing="0" BrushVersion="2" filename="$file" spacing="${f(eff.spacing)}" angle="${f(rad)}" brushApplication="0"/>"""
        var x = KppXml.setMainBrushDef(xml, def)
        x = KppXml.set(x, "requiredBrushFile", file)
        x = KppXml.set(x, "requiredBrushFilesList", file)
        return x
    }

    // ---------------------------------------------------------------- masking 组（旧写入器第 17 节）

    fun writeMasking(xml: String, p: BrushParams): String {
        var x = KppXml.set(xml, "MaskingBrush/Enabled", p.maskingEnabled.toString())
        x = KppXml.set(x, "MaskingBrush/MaskingCompositeOp", p.maskingCompositeOp)
        x = KppXml.set(x, "MaskingBrush/UseMasterSize", "true")
        x = KppXml.set(x, "MaskingBrush/MasterSizeCoeff", f(p.maskingSizeRatio))
        x = KppXml.set(x, "MaskingBrush/Preset/Spacing", f(p.maskingSpacing))
        x = KppXml.set(x, "MaskingBrush/Preset/paintopSize", f(p.size * p.maskingSizeRatio))
        val maskAngle = f(Math.toRadians(p.maskingAngle))
        val def = if (p.maskingTipAsset.isNotBlank()) {
            val fade = p.maskingFade.coerceIn(0.0, 1.0)
            val shape = if (p.maskingTipShape == 1) "rect" else "circle"
            val gen = """<MaskGenerator diameter="${f(p.size * p.maskingSizeRatio)}" hfade="${f(fade)}" vfade="${f(fade)}" id="default" spikes="2" type="$shape" ratio="1.0" antialiasEdges="1"/>"""
            val t = tipType(p.maskingTipAsset).let { if (it == "svg_brush") "png_brush" else it }
            """<Brush scale="1" type="$t" useAutoSpacing="0" BrushVersion="2" filename="${p.maskingTipAsset}" spacing="${f(p.maskingSpacing)}" angle="$maskAngle" brushApplication="0"> $gen </Brush>"""
        } else {
            KppXml.autoBrushDef(
                size = p.size * p.maskingSizeRatio,
                spacing = p.maskingSpacing,
                angleRad = Math.toRadians(p.maskingAngle),
                fade = p.maskingFade,
                shape = p.maskingTipShape,
                spikes = 2,
                ratio = 1.0,
                antiAliasing = 1,
            )
        }
        x = KppXml.set(x, KppXml.MASK_BRUSH_DEF, def)
        // 散布 / 翻转：键名同主笔刷的 Scatter / Mirror 选项（带 MaskingBrush/Preset/ 前缀，KisScatterOptionData / KisMirrorOption）
        val P = "MaskingBrush/Preset/"
        if (p.maskingScatter > TOL) {
            x = KppXml.set(x, "${P}ScatterValue", f(p.maskingScatter))
            x = KppXml.set(x, "${P}PressureScatter", "true")
            x = KppXml.set(x, "${P}ScatterUseCurve", "true")
            x = KppXml.set(x, "${P}ScatterSensor", KppSensors.single("fuzzy"))
            x = KppXml.set(x, "${P}Scattering/AxisX", p.maskingScatterBothAxes.toString())
            x = KppXml.set(x, "${P}Scattering/AxisY", "true")
        } else if (KppXml.has(x, "${P}PressureScatter")) {
            x = KppXml.set(x, "${P}PressureScatter", "false")
        }
        val flip = p.maskingFlipX || p.maskingFlipY
        if (flip || KppXml.has(x, "${P}PressureMirror")) {
            x = KppXml.set(x, "${P}PressureMirror", flip.toString())
            if (flip) {
                x = KppXml.set(x, "${P}MirrorUseCurve", "true")
                x = KppXml.set(x, "${P}MirrorSensor", KppSensors.single("fuzzy"))
            }
            if (p.maskingFlipX || KppXml.has(x, "${P}HorizontalMirrorEnabled")) x = KppXml.set(x, "${P}HorizontalMirrorEnabled", p.maskingFlipX.toString())
            if (p.maskingFlipY || KppXml.has(x, "${P}VerticalMirrorEnabled")) x = KppXml.set(x, "${P}VerticalMirrorEnabled", p.maskingFlipY.toString())
        }
        return x
    }

    // ---------------------------------------------------------------- 表

    private val hasDef: (PresetContext) -> Boolean = { !it.isMyPaint && it.hasBrushDef }
    private val hasGen: (PresetContext) -> Boolean = { !it.isMyPaint && it.hasMaskGenerator }
    private val smudgeOnly: (PresetContext) -> Boolean = { !it.isMyPaint && it.isColorSmudge }
    private val always: (PresetContext) -> Boolean = { true }

    private fun app(id: ControlId, get: (BrushParams) -> Any, set: (BrushParams, Any) -> BrushParams) =
        ControlSpec(id, get, set, applicable = always)

    val specs: List<ControlSpec> = buildList {
        // LIVE
        add(ControlSpec(ControlId.SIZE, { it.size }, { p, v -> p.copy(size = d(v)) }, read = ::readSize, applicable = always))
        add(
            ControlSpec(
                ControlId.OPACITY, { it.opacity }, { p, v -> p.copy(opacity = d(v)) },
                read = { KppXml.getDouble(it, "OpacityValue") ?: 1.0 },
                write = { x, v, _ -> KppXml.set(x, "OpacityValue", f(d(v))) },
                applicable = always,
            ),
        )
        add(
            ControlSpec(
                ControlId.FLOW, { it.flow }, { p, v -> p.copy(flow = d(v)) },
                read = { KppXml.getDouble(it, "FlowValue") ?: 1.0 },
                write = { x, v, _ -> KppXml.set(x, "FlowValue", f(d(v))) },
                applicable = always,
            ),
        )
        add(
            ControlSpec(
                ControlId.ANGLE, { it.angle }, { p, v -> p.copy(angle = d(v)) },
                read = { x -> KppXml.brushAttr(x, "angle")?.toDoubleOrNull()?.let { Math.toDegrees(it) } ?: 0.0.takeIf { KppXml.mainBrushDef(x) != null } },
                write = { x, v, _ -> KppXml.setBrushAttr(x, "angle", f(Math.toRadians(d(v)))) },
                applicable = { it.isMyPaint || it.hasBrushDef },
            ),
        )
        add(
            ControlSpec(
                ControlId.ROTATION, { it.rotation }, { p, v -> p.copy(rotation = d(v)) },
                read = { KppXml.getDouble(it, "ReverieRotation") ?: 0.0 },
                write = { x, v, _ -> KppXml.set(x, "ReverieRotation", f(d(v))) },
                applicable = always,
            ),
        )

        // REBUILD
        add(
            ControlSpec(
                ControlId.PAINTOP_ID, { it.paintOpId }, { p, v -> p.copy(paintOpId = s(v)) },
                read = { KppXml.paintOpId(it) },
                write = { x, v, _ ->
                    val id = s(v).let { if (it.isBlank() || it == "defaultpaintop") "paintbrush" else it }
                    KppXml.set(x.replace(Regex("""(<Preset\b[^>]*\bpaintopid=")[^"]*(")""")) { it.groupValues[1] + id + it.groupValues[2] }, "paintop", id)
                },
            ),
        )
        add(
            ControlSpec(
                ControlId.TIP_ASSET, { it.tipAsset }, { p, v -> p.copy(tipAsset = s(v)) },
                read = { x -> if (KppXml.mainBrushDef(x) == null) null else readTipAsset(x) },
                write = { x, v, eff -> writeTipAsset(x, s(v), eff) },
            ),
        )
        listOf(
            ControlId.CURVE_SIZE, ControlId.CURVE_OPACITY, ControlId.CURVE_FLOW, ControlId.CURVE_ROTATION,
            ControlId.CURVE_SCATTER, ControlId.CURVE_RATIO, ControlId.CURVE_SOFTNESS, ControlId.CURVE_SPACING,
            ControlId.CURVE_SMUDGE_RATE, ControlId.CURVE_COLOR_RATE,
        ).forEach { add(curveSpec(it)) }
        add(
            ControlSpec(
                ControlId.SPACING, { it.spacing }, { p, v -> p.copy(spacing = d(v)) },
                read = ::readSpacing, write = { x, v, _ -> writeSpacing(x, d(v)) }, applicable = hasDef,
            ),
        )
        add(strengthSpec(ControlId.SCATTER, "Scatter", 0.0, { it.scatter }, { p, v -> p.copy(scatter = v) }, { it > TOL }))
        // KisScatterOption::apply：AxisX=沿笔画方向，AxisY=沿法线；只开 AxisY 即 Photoshop 的 bothAxes=false。缺键按 KisScatterOptionData::read 默认 true
        add(
            ControlSpec(
                ControlId.SCATTER_BOTH_AXES, { it.scatterBothAxes }, { p, v -> p.copy(scatterBothAxes = b(v)) },
                read = { x -> KppXml.getBool(x, "Scattering/AxisX", true) },
                write = { x, v, _ -> KppXml.set(KppXml.set(x, "Scattering/AxisX", b(v).toString()), "Scattering/AxisY", "true") },
            ),
        )
        add(
            ControlSpec(
                ControlId.SOFTNESS, { it.softness }, { p, v -> p.copy(softness = d(v)) },
                read = { x -> KppSensors.readStrength(x, "Softness", 1.0).coerceIn(0.1, 1.0) },
                write = { x, v, _ ->
                    val sv = d(v).coerceIn(0.1, 1.0)
                    KppSensors.writeStrength(x, "Softness", sv, sv < 1.0 - TOL || curveActive(x, "Softness"))
                },
                normalize = { v -> d(v).coerceIn(0.1, 1.0) },
            ),
        )
        add(strengthSpec(ControlId.SHARPNESS, "Sharpness", 0.0, { it.sharpness }, { p, v -> p.copy(sharpness = v) }, { it > TOL }))
        add(
            ControlSpec(
                ControlId.RATIO, { it.ratio }, { p, v -> p.copy(ratio = d(v)) },
                read = { x ->
                    if (KppXml.hasMaskGenerator(x)) KppXml.maskGenAttr(x, "ratio")?.toDoubleOrNull() ?: 1.0
                    else KppSensors.readStrength(x, "Ratio", 1.0)
                },
                write = { x, v, _ ->
                    if (KppXml.hasMaskGenerator(x)) KppXml.setMaskGenAttr(x, "ratio", f(d(v)))
                    else KppSensors.writeStrength(x, "Ratio", d(v), d(v) < 1.0 - TOL || curveActive(x, "Ratio"))
                },
            ),
        )
        add(
            ControlSpec(
                ControlId.FADE, { it.fade }, { p, v -> p.copy(fade = d(v)) },
                read = { x ->
                    val h = KppXml.maskGenAttr(x, "hfade")?.toDoubleOrNull()
                    val vf = KppXml.maskGenAttr(x, "vfade")?.toDoubleOrNull()
                    listOfNotNull(h, vf).maxOrNull()
                },
                write = { x, v, _ ->
                    val fv = f(d(v).coerceIn(0.0, 1.0))
                    KppXml.setMaskGenAttr(KppXml.setMaskGenAttr(x, "hfade", fv), "vfade", fv)
                },
                applicable = hasGen,
                normalize = { v -> d(v).coerceIn(0.0, 1.0) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TIP_SHAPE, { it.tipShape }, { p, v -> p.copy(tipShape = i(v)) },
                read = { x -> KppXml.maskGenAttr(x, "type")?.let { if (it == "rect") 1 else 0 } },
                write = { x, v, _ -> KppXml.setMaskGenAttr(x, "type", if (i(v) == 1) "rect" else "circle") },
                applicable = hasGen,
                normalize = { v -> if (i(v) == 1) 1 else 0 },
            ),
        )
        add(
            ControlSpec(
                ControlId.SPIKES, { it.spikes }, { p, v -> p.copy(spikes = i(v)) },
                read = { x -> KppXml.maskGenAttr(x, "spikes")?.toDoubleOrNull()?.toInt() },
                write = { x, v, _ -> KppXml.setMaskGenAttr(x, "spikes", i(v).coerceAtLeast(2).toString()) },
                applicable = hasGen,
                normalize = { v -> i(v).coerceAtLeast(2) },
            ),
        )
        add(
            ControlSpec(
                ControlId.ANTI_ALIASING, { it.antiAliasing }, { p, v -> p.copy(antiAliasing = i(v)) },
                read = { x -> KppXml.maskGenAttr(x, "antialiasEdges")?.trim()?.let { if (it == "1" || it.equals("true", true)) 1 else 0 } },
                write = { x, v, _ -> KppXml.setMaskGenAttr(x, "antialiasEdges", if (i(v) > 0) "1" else "0") },
                applicable = hasGen,
                normalize = { v -> if (i(v) > 0) 1 else 0 },
            ),
        )
        add(
            ControlSpec(
                ControlId.COMPOSITE_OP, { it.compositeOp }, { p, v -> p.copy(compositeOp = BrushCompositeIds.canonical(s(v))) },
                read = { BrushCompositeIds.canonical(KppXml.get(it, "CompositeOp")?.trim()?.ifBlank { null } ?: "normal") },
                write = { x, v, _ -> if (s(v).isBlank()) x else KppXml.set(x, "CompositeOp", BrushCompositeIds.canonical(s(v))) },
            ),
        )
        add(mirrorSpec(ControlId.RANDOM_FLIP_X, "HorizontalMirrorEnabled", "VerticalMirrorEnabled", { it.randomFlipX }) { p, v -> p.copy(randomFlipX = v) })
        add(mirrorSpec(ControlId.RANDOM_FLIP_Y, "VerticalMirrorEnabled", "HorizontalMirrorEnabled", { it.randomFlipY }) { p, v -> p.copy(randomFlipY = v) })
        add(
            ControlSpec(
                ControlId.FOLLOW_DIRECTION, { it.followDirection }, { p, v -> p.copy(followDirection = b(v)) },
                read = { x ->
                    KppSensors.isChecked(x, "Rotation") && KppSensors.useCurve(x, "Rotation") &&
                        "drawingangle" in KppSensors.sensorIds(KppSensors.sensorXml(x, "Rotation"))
                },
                write = { x, v, _ -> writeFollowDirection(x, b(v)) },
            ),
        )
        add(mergedJitterSpec(ControlId.JITTER_ANGLE, "Rotation", { it.jitterAngle }) { p, v -> p.copy(jitterAngle = v) })
        add(mergedJitterSpec(ControlId.JITTER_SIZE, "Size", { it.jitterSize }) { p, v -> p.copy(jitterSize = v) })
        add(
            ControlSpec(
                ControlId.TEXTURE_ENABLED, { it.textureEnabled }, { p, v -> p.copy(textureEnabled = b(v)) },
                read = { KppXml.getBool(it, "Texture/Pattern/Enabled", false) },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/Enabled", b(v).toString()) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_SCALE, { it.textureScale }, { p, v -> p.copy(textureScale = d(v)) },
                read = { KppXml.getDouble(it, "Texture/Pattern/Scale") ?: 1.0 },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/Scale", f(d(v))) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_STRENGTH, { it.textureStrength }, { p, v -> p.copy(textureStrength = d(v)) },
                read = ::readTextureStrength,
                write = { x, v, _ -> KppSensors.writeStrength(x, TEXTURE_STRENGTH_OPTION, d(v), true) },
            ),
        )
        // Krita「纹理 → 强度」选项（KisStrengthOptionData，id "Texture/Strength/"）自带传感器曲线：
        // 勾选 + UseCurve + pressure 传感器 = 纹理深度随压力变化（对应 PS textureDepthDynamics 控制=钢笔压力）
        add(
            ControlSpec(
                ControlId.TEXTURE_STRENGTH_PRESSURE, { it.textureStrengthPressureMin }, { p, v -> p.copy(textureStrengthPressureMin = d(v)) },
                read = { x ->
                    val o = TEXTURE_STRENGTH_OPTION
                    if (KppSensors.isChecked(x, o) && KppSensors.useCurve(x, o) && "pressure" in KppSensors.sensorIds(KppSensors.sensorXml(x, o))) {
                        KppSensors.firstCurve(KppSensors.sensorXml(x, o))?.split(';')?.firstOrNull()?.split(',')?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    } else -1.0
                },
                write = { x0, v, _ ->
                    val o = TEXTURE_STRENGTH_OPTION
                    val min = d(v)
                    if (min < 0.0) {
                        if (KppSensors.useCurve(x0, o) && "pressure" in KppSensors.sensorIds(KppSensors.sensorXml(x0, o))) KppXml.set(x0, "${o}UseCurve", "false") else x0
                    } else {
                        var x = KppXml.set(x0, KppSensors.pressureKey(o), "true")
                        x = KppXml.set(x, "${o}UseCurve", "true")
                        val curve = "0,${f(min.coerceIn(0.0, 1.0))};1,1;"
                        x = KppSensors.writeSensor(x, o, KppSensors.single("pressure", curve))
                        KppXml.set(x, "${o}commonCurve", curve)
                    }
                },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_MODE, { TextureModes.toInt(it.textureMode) }, { p, v -> p.copy(textureMode = TextureModes.nameOf(i(v))) },
                read = { KppXml.getInt(it, "Texture/Pattern/TexturingMode") ?: 0 },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/TexturingMode", i(v).coerceIn(0, TextureModes.NAMES.size - 1).toString()) },
                normalize = { v -> if (v is String) TextureModes.toInt(v) else i(v) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_INVERT, { it.textureInvert }, { p, v -> p.copy(textureInvert = b(v)) },
                read = { KppXml.getBool(it, "Texture/Pattern/Invert", false) },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/Invert", b(v).toString()) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_SOFT, { it.textureSoft }, { p, v -> p.copy(textureSoft = b(v)) },
                read = { KppXml.getBool(it, "Texture/Pattern/UseSoftTexturing", false) },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/UseSoftTexturing", b(v).toString()) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_BRIGHTNESS, { it.textureBrightness }, { p, v -> p.copy(textureBrightness = d(v)) },
                read = { KppXml.getDouble(it, "Texture/Pattern/Brightness") ?: 0.0 },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/Brightness", f(d(v))) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_CONTRAST, { it.textureContrast }, { p, v -> p.copy(textureContrast = d(v)) },
                read = { KppXml.getDouble(it, "Texture/Pattern/Contrast") ?: 1.0 },
                write = { x, v, _ -> KppXml.set(x, "Texture/Pattern/Contrast", f(d(v))) },
            ),
        )
        add(
            ControlSpec(
                ControlId.TEXTURE_PATTERN, { it.texturePattern }, { p, v -> p.copy(texturePattern = s(v)) },
                read = { x -> KppXml.get(x, "Texture/Pattern/PatternFileName")?.ifBlank { null } ?: KppXml.get(x, "Texture/Pattern/Name") ?: "" },
                write = { x, v, _ -> writeTexturePattern(x, ResourceStore.Ref(md5 = "", file = s(v), kind = ResourceStore.Kind.PATTERN)) },
            ),
        )
        add(jitterSpec(ControlId.HUE_JITTER, "h", { it.hueJitter }) { p, v -> p.copy(hueJitter = v) })
        add(jitterSpec(ControlId.SAT_JITTER, "s", { it.satJitter }) { p, v -> p.copy(satJitter = v) })
        add(jitterSpec(ControlId.VAL_JITTER, "v", { it.valJitter }) { p, v -> p.copy(valJitter = v) })
        add(strengthSpec(ControlId.MIX, "Mix", 0.0, { it.secondaryMix }, { p, v -> p.copy(secondaryMix = v) }, { it > TOL }))
        add(
            ControlSpec(
                ControlId.MIX_PRESSURE, { it.pressureColorMix }, { p, v -> p.copy(pressureColorMix = b(v)) },
                read = { x ->
                    KppSensors.isChecked(x, "Mix") && KppSensors.useCurve(x, "Mix") &&
                        "pressure" in KppSensors.sensorIds(KppSensors.sensorXml(x, "Mix"))
                },
                write = { x, v, _ -> writeMixPressure(x, b(v)) },
            ),
        )
        add(strengthSpec(ControlId.COLOR_RATE, "ColorRate", 0.0, { it.colorRate }, { p, v -> p.copy(colorRate = v, smudgeRate = v) }, { it > TOL }, smudgeOnly))
        add(strengthSpec(ControlId.SMUDGE_LENGTH, "SmudgeRate", 0.0, { it.smudgeLength }, { p, v -> p.copy(smudgeLength = v) }, { it > TOL }, smudgeOnly))
        add(
            ControlSpec(
                ControlId.SMUDGE_MODE, { it.smudgeMode }, { p, v -> p.copy(smudgeMode = i(v)) },
                read = { KppXml.getInt(it, "SmudgeRateMode") ?: 0 },
                write = { x, v, _ -> KppXml.set(x, "SmudgeRateMode", i(v).coerceIn(0, 1).toString()) },
                applicable = smudgeOnly,
                normalize = { v -> i(v).coerceIn(0, 1) },
            ),
        )
        add(
            ControlSpec(
                ControlId.AIRBRUSH, { it.airbrush }, { p, v -> p.copy(airbrush = b(v)) },
                read = { x ->
                    when {
                        KppXml.has(x, "PaintOpSettings/isAirbrushing") -> KppXml.getBool(x, "PaintOpSettings/isAirbrushing", false)
                        else -> KppXml.getBool(x, "AirbrushOption/isAirbrushing", false)
                    }
                },
                write = { x, v, _ -> writeAirbrushKey(x, "isAirbrushing", b(v).toString()) },
            ),
        )
        add(
            ControlSpec(
                ControlId.AIRBRUSH_RATE, { it.airbrushRate }, { p, v -> p.copy(airbrushRate = d(v)) },
                read = { x -> KppXml.getDouble(x, "PaintOpSettings/rate") ?: KppXml.getDouble(x, "AirbrushOption/rate") },
                write = { x, v, _ -> writeAirbrushKey(x, "rate", f(d(v))) },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_ENABLED, { it.maskingEnabled }, { p, v -> p.copy(maskingEnabled = b(v)) },
                read = { KppXml.getBool(it, "MaskingBrush/Enabled", false) },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_COMPOSITE_OP, { it.maskingCompositeOp }, { p, v -> p.copy(maskingCompositeOp = s(v)) },
                read = { KppXml.get(it, "MaskingBrush/MaskingCompositeOp")?.trim()?.ifBlank { null } },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_SIZE_RATIO, { it.maskingSizeRatio }, { p, v -> p.copy(maskingSizeRatio = d(v)) },
                read = { KppXml.getDouble(it, "MaskingBrush/MasterSizeCoeff") },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_SPACING, { it.maskingSpacing }, { p, v -> p.copy(maskingSpacing = d(v)) },
                read = { x -> maskDefAttr(x, "Brush", "spacing")?.toDoubleOrNull() ?: KppXml.getDouble(x, "MaskingBrush/Preset/Spacing") },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_TIP_ASSET, { it.maskingTipAsset }, { p, v -> p.copy(maskingTipAsset = s(v)) },
                read = { x ->
                    val t = maskDefAttr(x, "Brush", "type")
                    when {
                        t == null -> null
                        t == "auto_brush" -> ""
                        else -> maskDefAttr(x, "Brush", "filename").orEmpty()
                    }
                },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_TIP_SHAPE, { it.maskingTipShape }, { p, v -> p.copy(maskingTipShape = i(v)) },
                read = { x -> maskDefAttr(x, "MaskGenerator", "type")?.let { if (it == "rect") 1 else 0 } },
                normalize = { v -> if (i(v) == 1) 1 else 0 },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_FADE, { it.maskingFade }, { p, v -> p.copy(maskingFade = d(v)) },
                read = { x -> maskDefAttr(x, "MaskGenerator", "hfade")?.toDoubleOrNull() },
                normalize = { v -> d(v).coerceIn(0.0, 1.0) },
            ),
        )

        add(
            ControlSpec(
                ControlId.MASKING_SCATTER, { it.maskingScatter }, { p, v -> p.copy(maskingScatter = d(v)) },
                read = { x ->
                    if (!KppXml.has(x, "MaskingBrush/Preset/ScatterValue")) null
                    else if (KppXml.getBool(x, "MaskingBrush/Preset/PressureScatter", false)) KppXml.getDouble(x, "MaskingBrush/Preset/ScatterValue")
                    else 0.0
                },
            ),
        )
        // 同主笔刷 SCATTER_BOTH_AXES：AxisX=沿笔画方向、AxisY=沿法线；缺键按 KisScatterOptionData 默认 true
        add(
            ControlSpec(
                ControlId.MASKING_SCATTER_BOTH_AXES, { it.maskingScatterBothAxes }, { p, v -> p.copy(maskingScatterBothAxes = b(v)) },
                read = { x ->
                    if (!KppXml.has(x, "MaskingBrush/Enabled")) null
                    else KppXml.getBool(x, "MaskingBrush/Preset/Scattering/AxisX", true)
                },
            ),
        )
        add(
            ControlSpec(
                ControlId.MASKING_ANGLE, { it.maskingAngle }, { p, v -> p.copy(maskingAngle = d(v)) },
                read = { x -> maskDefAttr(x, "Brush", "angle")?.toDoubleOrNull()?.let { Math.toDegrees(it) } },
            ),
        )
        add(maskFlipSpec(ControlId.MASKING_FLIP_X, "HorizontalMirrorEnabled", { it.maskingFlipX }) { p, v -> p.copy(maskingFlipX = v) })
        add(maskFlipSpec(ControlId.MASKING_FLIP_Y, "VerticalMirrorEnabled", { it.maskingFlipY }) { p, v -> p.copy(maskingFlipY = v) })

        // APP_ONLY
        add(app(ControlId.MASKING_SOFTNESS, { it.maskingSoftness }) { p, v -> p.copy(maskingSoftness = d(v)) })
        add(app(ControlId.STREAMLINE, { it.streamline }) { p, v -> p.copy(streamline = d(v)) })
        add(app(ControlId.TAPER, { it.taper }) { p, v -> p.copy(taper = d(v)) })
        add(app(ControlId.SPEED_SIZE, { it.speedSize }) { p, v -> p.copy(speedSize = d(v)) })
        add(app(ControlId.MIN_SIZE_LIMIT, { it.minSizeLimit }) { p, v -> p.copy(minSizeLimit = d(v)) })
        add(app(ControlId.MAX_SIZE_LIMIT, { it.maxSizeLimit }) { p, v -> p.copy(maxSizeLimit = d(v)) })
        add(app(ControlId.PRESSURE_ENABLED, { it.pressureEnabled }) { p, v -> p.copy(pressureEnabled = b(v)) })
        add(app(ControlId.PRESSURE_CURVE, { it.pressureCurve }) { p, v -> p.copy(pressureCurve = i(v)) })
        add(app(ControlId.AUTHOR, { it.author }) { p, v -> p.copy(author = s(v)) })
        add(app(ControlId.AUTHOR_LOCKED, { it.isAuthorLocked }) { p, v -> p.copy(isAuthorLocked = b(v)) })
        add(app(ControlId.DESCRIPTION, { it.description }) { p, v -> p.copy(description = s(v)) })
        add(app(ControlId.VERSION, { it.version }) { p, v -> p.copy(version = s(v)) })
    }

    private val byId: Map<ControlId, ControlSpec> = specs.associateBy { it.id }

    init {
        check(byId.size == ControlId.entries.size) { "PresetPropertyMap 缺少控件: ${ControlId.entries - byId.keys}" }
    }

    fun spec(id: ControlId): ControlSpec = byId.getValue(id)

    const val TEXTURE_STRENGTH_OPTION = "Texture/Strength/"

    fun readTextureStrength(xml: String): Double? {
        if (KppXml.has(xml, "${TEXTURE_STRENGTH_OPTION}Value") || KppXml.has(xml, KppSensors.pressureKey(TEXTURE_STRENGTH_OPTION))) {
            return KppSensors.readStrength(xml, TEXTURE_STRENGTH_OPTION, 1.0)
        }
        return KppXml.getDouble(xml, "Texture/Pattern/Strength") ?: KppXml.getDouble(xml, "Texture/StrengthValue")
    }

    fun patternRefKeys(ref: ResourceStore.Ref): Map<String, String> = linkedMapOf(
        "Texture/Pattern/PatternFileName" to ref.file,
        "Texture/Pattern/Name" to ref.file,
        "Texture/Pattern/PatternMD5Sum" to ref.md5,
        "Texture/Pattern/PatternMD5" to "",
    )

    /** 换纹理：先删 md5 与内嵌图（Krita 按 md5 优先匹配，KisEmbeddedTextureData.cpp:32-44），再写文件名 */
    fun writeTexturePattern(xml: String, ref: ResourceStore.Ref): String {
        if (ref.file.isBlank()) return xml
        val cur = KppXml.get(xml, "Texture/Pattern/PatternFileName")
        if (cur == ref.file && KppXml.get(xml, "Texture/Pattern/Name") == ref.file) return xml
        var x = KppXml.remove(xml, "Texture/Pattern/Pattern")
        for ((k, v) in patternRefKeys(ref)) {
            x = KppXml.set(x, k, v)
        }
        return x
    }

    private fun writeAirbrushKey(xml: String, suffix: String, value: String): String {
        var x = KppXml.set(xml, "PaintOpSettings/$suffix", value)
        // 旧键存在时同步，避免导出后两个键互相矛盾
        if (KppXml.has(x, "AirbrushOption/$suffix")) x = KppXml.set(x, "AirbrushOption/$suffix", value)
        return x
    }

    /** masking 预设里的翻转（只读；整组写入见 [writeMasking]） */
    private fun maskFlipSpec(id: ControlId, key: String, get: (BrushParams) -> Boolean, set: (BrushParams, Boolean) -> BrushParams) =
        ControlSpec(
            id,
            get = { get(it) },
            set = { p, v -> set(p, b(v)) },
            read = { x ->
                if (!KppXml.has(x, "MaskingBrush/Enabled")) null
                else KppXml.getBool(x, "MaskingBrush/Preset/PressureMirror", false) && KppXml.getBool(x, "MaskingBrush/Preset/$key", false)
            },
        )

    private fun mirrorSpec(
        id: ControlId,
        ownKey: String,
        otherKey: String,
        get: (BrushParams) -> Boolean,
        set: (BrushParams, Boolean) -> BrushParams,
    ) = ControlSpec(
        id,
        get = { get(it) },
        set = { p, v -> set(p, b(v)) },
        read = { x -> KppSensors.isChecked(x, "Mirror") && KppXml.getBool(x, ownKey, false) },
        write = { x0, v, _ ->
            val on = b(v)
            val wasChecked = KppSensors.isChecked(x0, "Mirror")
            val any = on || (wasChecked && KppXml.getBool(x0, otherKey, false))
            var x = KppXml.set(x0, ownKey, on.toString())
            if (any) {
                x = KppXml.set(x, "PressureMirror", "true")
                val s0 = KppSensors.sensorXml(x0, "Mirror")
                if (!wasChecked || "fuzzy" !in KppSensors.sensorIds(s0)) {
                    x = KppXml.set(x, "MirrorUseCurve", "true")
                    x = KppSensors.writeSensor(x, "Mirror", if (wasChecked) KppSensors.addSensor(s0, "fuzzy") else KppSensors.single("fuzzy"))
                }
                // 未勾选时另一侧的旧开关会被一起激活：保持其为 false，除非它本来就在生效
                if (!wasChecked) x = KppXml.set(x, otherKey, "false")
            } else {
                x = KppXml.set(x, "PressureMirror", "false")
            }
            x
        },
    )

    fun writeFollowDirection(xml: String, on: Boolean): String {
        val s0 = KppSensors.sensorXml(xml, "Rotation")
        val checked = KppSensors.isChecked(xml, "Rotation")
        return if (on) {
            var x = KppXml.set(xml, "PressureRotation", "true")
            x = KppXml.set(x, "RotationUseCurve", "true")
            if (!checked) x = KppXml.set(x, "RotationValue", "1")
            if (checked && "drawingangle" in KppSensors.sensorIds(s0)) x
            else KppSensors.writeSensor(x, "Rotation", if (checked && s0 != null) KppSensors.addSensor(s0, "drawingangle") else drawingAngleSensor(KppSensors.LINEAR))
        } else {
            // 读值已是 false（未勾选 / UseCurve 关）时不动结构，避免恒等写改掉未生效的传感器列表
            if (!checked || !KppSensors.useCurve(xml, "Rotation")) return xml
            if ("drawingangle" !in KppSensors.sensorIds(s0)) return xml
            val rest = KppSensors.removeSensor(s0, "drawingangle")
            if (rest == null) KppXml.set(xml, "PressureRotation", "false") else KppSensors.writeSensor(xml, "Rotation", rest)
        }
    }

    fun writeMixPressure(xml: String, on: Boolean): String {
        val s0 = KppSensors.sensorXml(xml, "Mix")
        val checked = KppSensors.isChecked(xml, "Mix")
        return if (on) {
            var x = KppXml.set(xml, "PressureMix", "true")
            x = KppXml.set(x, "MixUseCurve", "true")
            if (checked && "pressure" in KppSensors.sensorIds(s0)) x
            else KppSensors.writeSensor(x, "Mix", if (checked && s0 != null) KppSensors.addSensor(s0, "pressure") else KppSensors.single("pressure"))
        } else {
            if (!checked || !KppSensors.useCurve(xml, "Mix")) return xml
            if ("pressure" !in KppSensors.sensorIds(s0)) return xml
            val rest = KppSensors.removeSensor(s0, "pressure")
            if (rest == null) KppXml.set(xml, "MixUseCurve", "false") else KppSensors.writeSensor(xml, "Mix", rest)
        }
    }

    // ---------------------------------------------------------------- 整体 API

    /** 两个控件值是否等价（数值容差 [TOL]，曲线按点比较） */
    fun same(id: ControlId, a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == b
        val sp = spec(id)
        val na = sp.normalize(a)
        val nb = sp.normalize(b)
        if (id.curveOption != null) {
            val ca = CurveValue.parse(na as? String)
            val cb = CurveValue.parse(nb as? String)
            return if (ca == null || cb == null) ca == cb else ca.sameAs(cb)
        }
        if (na is Number && nb is Number) return abs(na.toDouble() - nb.toDouble()) <= TOL
        return na == nb
    }

    /** 从 kpp XML 读出每个可读控件的值（只含该预设适用且读得出值的控件） */
    fun readValues(xml: String, ctx: PresetContext = PresetContext.of(xml)): Map<ControlId, Any> {
        val out = LinkedHashMap<ControlId, Any>()
        for (sp in specs) {
            val r = sp.read ?: continue
            if (!sp.applicable(ctx)) continue
            r(xml)?.let { out[sp.id] = it }
        }
        return out
    }

    /**
     * 预设的"出厂视图"：[base] 之上叠加 [readValues]，再派生压感/传感器等非控件字段。
     * .myb 没有 XML，调用方传 [PresetContext.MYPAINT] 时只返回 [base]。
     */
    fun readParams(xml: String?, base: BrushParams = BrushParams()): BrushParams {
        if (xml == null) return base
        var p = base
        for ((id, v) in readValues(xml)) p = spec(id).set(p, v)
        return deriveFields(p)
    }

    /** 由曲线控件派生旧字段（pressureSize / sizeSensor 等），这些字段不是独立控件 */
    fun deriveFields(p: BrushParams): BrushParams {
        fun c(k: String) = CurveValue.parse(p.dynamicOptions[k])
        val size = c("Size")
        val op = c("Opacity")
        val fl = c("Flow")
        val rot = c("Rotation")
        val sc = c("Scatter")
        return p.copy(
            pressureSize = size?.let { if (it.enabled) it.strength else 0.0 } ?: p.pressureSize,
            pressureOpacity = op?.let { if (it.enabled) it.strength else 0.0 } ?: p.pressureOpacity,
            pressureFlow = fl?.let { if (it.enabled) it.strength else 0.0 } ?: p.pressureFlow,
            sizeSensor = size?.sensorId ?: p.sizeSensor,
            opacitySensor = op?.sensorId ?: p.opacitySensor,
            flowSensor = fl?.sensorId ?: p.flowSensor,
            rotationSensor = rot?.let { if (it.enabled) it.sensorId else "" } ?: p.rotationSensor,
            scatterSensor = sc?.sensorId ?: p.scatterSensor,
        )
    }

    /** [edited] 相对 [base] 改动了哪些控件（只算对 [ctx] 适用的）；值为 [edited] 中的值 */
    fun diff(base: BrushParams, edited: BrushParams, ctx: PresetContext): Map<ControlId, Any> {
        val out = LinkedHashMap<ControlId, Any>()
        for (sp in specs) {
            if (!sp.applicable(ctx)) continue
            val e = sp.get(edited)
            if (sp.id.curveOption != null && (e as String).isBlank()) continue
            if (!same(sp.id, sp.get(base), e)) out[sp.id] = e
        }
        return out
    }

    /** 把覆盖值叠加到 [base] 上得到有效参数 */
    fun applyToParams(base: BrushParams, controls: Map<ControlId, Any>): BrushParams {
        var p = base
        for (sp in specs) controls[sp.id]?.let { p = sp.set(p, it) }
        return deriveFields(p)
    }

    /**
     * 按表写入覆盖：只写 [controls] 里出现、可写且适用的控件，按声明顺序；masking 组只要有一个
     * 被覆盖就从 [eff] 整组写。返回新 XML；[controls] 为空时原样返回。
     */
    fun applyToXml(xml: String, controls: Map<ControlId, Any>, eff: BrushParams): String {
        if (controls.isEmpty()) return xml
        val ctx = PresetContext.of(xml)
        var x = xml
        val groups = LinkedHashSet<String>()
        for (sp in specs) {
            val v = controls[sp.id] ?: continue
            if (!sp.applicable(ctx)) continue
            val g = sp.id.group
            if (g != null) {
                groups += g
                continue
            }
            val w = sp.write ?: continue
            x = w(x, v, eff)
        }
        if ("masking" in groups) x = writeMasking(x, eff)
        return x
    }
}
