/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线强度一等化契约（任务书 K1）：
 * - 共享通道（非 Size/Spacing）的强度持久化在 `<Id>DynamicStrength`，原始曲线在 `<Id>RawCurve`，
 *   往返 read(write(xml, v)) == v 零损耗；引擎实际使用的 `<Id>Sensor` 主曲线按强度烘焙。
 * - OWNERS（Size/Spacing）强度仍走 `<Id>Value`，不写 DynamicStrength/RawCurve。
 * - JITTER_SIZE / SPEED_SIZE 走 SizeSensor 的 fuzzy / speed 子传感器，不再占用 SizeValue。
 * - 例外：5C-2 保活分支（keepSizeSubSensors 关闭态）会写 `SizeRawCurve`，是"Size/Spacing 关闭态不写 RawCurve"的例外。
 */
class CurveStrengthBakeTest {

    private fun preset(vararg params: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Preset name=\"T\" paintopid=\"paintbrush\">")
        for ((k, v) in params) append("<param type=\"string\" name=\"$k\"><![CDATA[$v]]></param>")
        append("</Preset>")
    }

    // ---------------------------------------------------------------- bakeCurve

    private val c = "0,0;0.5,0.5;1,1;"

    @Test
    fun bakeCurve_attenuating_endpoints() {
        // 衰减类（Opacity/Flow）：y' = (1-s) + s*y
        assertEquals(c, PresetPropertyMap.bakeCurve(c, true, 1.0))
        assertEquals("0,1;0.5,1;1,1;", PresetPropertyMap.bakeCurve(c, true, 0.0))
        assertEquals("0,0.5;0.5,0.75;1,1;", PresetPropertyMap.bakeCurve(c, true, 0.5))
    }

    @Test
    fun bakeCurve_modulating_endpoints() {
        // 调制类：y' = s*y
        assertEquals("0,0;0.5,0;1,0;", PresetPropertyMap.bakeCurve(c, false, 0.0))
        assertEquals("0,0;0.5,0.25;1,0.5;", PresetPropertyMap.bakeCurve(c, false, 0.5))
    }

    @Test
    fun bakeCurve_unparseableReturnsAsIs() {
        assertEquals("garbage", PresetPropertyMap.bakeCurve("garbage", true, 0.5))
        assertEquals("", PresetPropertyMap.bakeCurve("", false, 0.5))
    }

    // ---------------------------------------------------------------- 共享通道往返

    @Test
    fun sharedChannel_roundTripsStrength_withoutCurveLoss() {
        val curve = "0,0;0.5,0.4;1,1;"
        val xml = preset(
            "OpacitySensor" to KppSensors.single("pressure", curve),
            "OpacityUseCurve" to "true",
        )
        val v = CurveValue(enabled = true, sensorId = "pressure", curve = curve, strength = 0.35)
        val out = PresetPropertyMap.writeCurve(xml, "Opacity", v)

        // 强度与原始曲线零损耗回读
        val back = PresetPropertyMap.readCurve(out, "Opacity")!!
        assertEquals(0.35, back.strength, 1e-9)
        assertEquals(CurveValue.normalizeCurve(curve), back.curve)
        assertTrue(back.sameAs(v))

        // 键存在：DynamicStrength / RawCurve
        assertEquals("0.35", KppXml.get(out, "OpacityDynamicStrength"))
        assertEquals(CurveValue.normalizeCurve(curve), KppXml.get(out, "OpacityRawCurve"))

        // 引擎实际用的 Sensor 主曲线已按衰减类烘焙：中点 (1-0.35)+0.35*0.4 = 0.79
        val baked = KppSensors.sensorCurve(KppSensors.sensorXml(out, "Opacity")!!, "pressure")!!
        assertEquals(0.79, CurveValue.points(baked)[1].second, 1e-6)
    }

    @Test
    fun ownersChannel_writesValue_only() {
        val sizeXml = preset(
            "SizeSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
        )
        val out = PresetPropertyMap.writeCurve(sizeXml, "Size", CurveValue(true, "pressure", KppSensors.LINEAR, 0.7))
        assertEquals("0.7", KppXml.get(out, "SizeValue"))
        assertNull("OWNERS 不写 DynamicStrength", KppXml.get(out, "SizeDynamicStrength"))
        assertNull("OWNERS 不写 RawCurve", KppXml.get(out, "SizeRawCurve"))

        val spacingXml = preset(
            "SpacingSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureSpacing" to "true",
            "SpacingUseCurve" to "true",
        )
        val outS = PresetPropertyMap.writeCurve(spacingXml, "Spacing", CurveValue(true, "pressure", KppSensors.LINEAR, 0.6))
        assertEquals("0.6", KppXml.get(outS, "SpacingValue"))
        assertNull(KppXml.get(outS, "SpacingDynamicStrength"))
    }

    @Test
    fun disabledCurve_stillWritesStrengthChannels() {
        val xml = preset(
            "ScatterSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureScatter" to "true",
            "ScatterUseCurve" to "true",
        )
        val out = PresetPropertyMap.writeCurve(xml, "Scatter", CurveValue(false, "pressure", KppSensors.LINEAR, 0.5))
        assertEquals("false", KppXml.get(out, "ScatterUseCurve"))
        // 勾选保留（不取消 PressureScatter）：取消会让 readStrength 走 unchecked 分支，主滑块读值被清零
        assertEquals("true", KppXml.get(out, "PressureScatter"))
        assertEquals("0.5", KppXml.get(out, "ScatterDynamicStrength"))
        assertEquals(CurveValue.normalizeCurve(KppSensors.LINEAR), KppXml.get(out, "ScatterRawCurve"))
    }

    @Test
    fun nativeStyleXml_readsStrengthOneAndOriginalCurve() {
        val curve = "0,0.1;1,0.9;"
        val xml = preset(
            "OpacitySensor" to KppSensors.single("pressure", curve),
            "OpacityUseCurve" to "true",
        )
        val v = PresetPropertyMap.readCurve(xml, "Opacity")!!
        assertEquals(1.0, v.strength, 0.0)
        assertEquals(CurveValue.normalizeCurve(curve), v.curve)
    }

    @Test
    fun curveWrite_bakesPrimaryOnly_keepsFuzzySubSensor() {
        val listSensor = "<!DOCTYPE params><params id=\"sensorslist\">" +
            "<ChildSensor id=\"pressure\"><curve>0,0;1,1;</curve></ChildSensor>" +
            "<ChildSensor id=\"fuzzy\"><curve>0,0.8;1,1;</curve></ChildSensor></params>"
        val xml = preset(
            "SoftnessSensor" to listSensor,
            "PressureSoftness" to "true",
            "SoftnessUseCurve" to "true",
            "SoftnessValue" to "1.0",
        )
        val out = PresetPropertyMap.writeCurve(xml, "Softness", CurveValue(true, "pressure", "0,0;1,1;", 0.5))
        val s = KppSensors.sensorXml(out, "Softness")!!
        assertEquals(listOf("pressure", "fuzzy"), KppSensors.sensorIds(s))
        // 调制类只烘焙主传感器：y' = 0.5*y
        assertEquals(0.5, CurveValue.points(KppSensors.sensorCurve(s, "pressure")!!)[1].second, 1e-9)
        // fuzzy 子传感器原样保留
        assertEquals("0,0.8;1,1;", KppSensors.sensorCurve(s, "fuzzy"))
        assertEquals("false", KppXml.get(out, "SoftnessUseSameCurve"))
    }

    // ---------------------------------------------------------------- JITTER_SIZE / SPEED_SIZE

    @Test
    fun jitterSize_writesFuzzyCurve_notSizeValue() {
        val xml = preset(
            "SizeSensor" to KppSensors.single("pressure", "0,0.2;1,1;"),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
            "SizeValue" to "0.8",
        )
        val out = PresetPropertyMap.writeJitterSize(xml, 0.3)
        assertEquals("SizeValue 不再被抖动覆写", "0.8", KppXml.get(out, "SizeValue"))
        val s = KppSensors.sensorXml(out, "Size")!!
        assertEquals("0,0.7;1,1;", KppSensors.sensorCurve(s, "fuzzy"))
        assertEquals("false", KppXml.get(out, "SizeUseSameCurve"))
        assertEquals(0.3, PresetPropertyMap.readJitterSize(out), 1e-9)
    }

    @Test
    fun jitterSize_zeroIsNoOp_missingSensorCreatesFuzzy() {
        val xml = preset("SizeValue" to "0.8")
        assertEquals(xml, PresetPropertyMap.writeJitterSize(xml, 0.0))
        // 没有 SizeSensor 时新建单个 fuzzy（旧 mergedJitterSpec 行为），否则抖动永远无法生效
        val out = PresetPropertyMap.writeJitterSize(xml, 0.3)
        assertEquals(listOf("fuzzy"), KppSensors.sensorIds(KppSensors.sensorXml(out, "Size")))
        assertEquals(0.3, PresetPropertyMap.readJitterSize(out), 1e-9)
        assertEquals(0.0, PresetPropertyMap.readJitterSize(xml), 0.0)
    }

    @Test
    fun jitterSize_zeroRemovesActiveFuzzy() {
        val xml = preset(
            "SizeSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
        )
        val on = PresetPropertyMap.writeJitterSize(xml, 0.3)
        assertEquals(0.3, PresetPropertyMap.readJitterSize(on), 1e-9)
        val off = PresetPropertyMap.writeJitterSize(on, 0.0)
        assertEquals(0.0, PresetPropertyMap.readJitterSize(off), 0.0)
        assertEquals(listOf("pressure"), KppSensors.sensorIds(KppSensors.sensorXml(off, "Size")!!))
    }

    @Test
    fun speedSize_writesSpeedCurve_roundTrips() {
        val xml = preset(
            "SizeSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
        )
        val out = PresetPropertyMap.writeSpeedSize(xml, 0.4)
        val s = KppSensors.sensorXml(out, "Size")!!
        val pts = CurveValue.points(KppSensors.sensorCurve(s, "speed")!!)
        assertEquals(1 - 0.28, pts.last().second, 1e-9)
        assertEquals("false", KppXml.get(out, "SizeUseSameCurve"))
        assertEquals(0.4, PresetPropertyMap.readSpeedSize(out), 1e-9)
    }

    @Test
    fun speedSize_activatesSizeOption_andGatesOnInactive() {
        // Size 选项未激活：读 0；写入后激活勾选 + UseCurve（与 writeJitterSize 一致）
        val xml = preset("SizeSensor" to KppSensors.single("pressure", KppSensors.LINEAR))
        assertEquals(0.0, PresetPropertyMap.readSpeedSize(xml), 0.0)
        val out = PresetPropertyMap.writeSpeedSize(xml, 0.4)
        assertEquals("true", KppXml.get(out, "PressureSize"))
        assertEquals("true", KppXml.get(out, "SizeUseCurve"))
        assertEquals(0.4, PresetPropertyMap.readSpeedSize(out), 1e-9)
        // 曲线关闭后读 0
        val off = KppXml.set(out, "SizeUseCurve", "false")
        assertEquals(0.0, PresetPropertyMap.readSpeedSize(off), 0.0)
    }

    @Test
    fun speedSize_removeOnZero() {
        val xml = preset(
            "SizeSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
        )
        val on = PresetPropertyMap.writeSpeedSize(xml, 0.4)
        val off = PresetPropertyMap.writeSpeedSize(on, 0.0)
        assertEquals(listOf("pressure"), KppSensors.sensorIds(KppSensors.sensorXml(off, "Size")))
        assertNull("speed 不再存在", KppSensors.sensorCurve(KppSensors.sensorXml(off, "Size"), "speed"))
        // speed 是唯一子传感器时移除整个 SizeSensor
        val speedOnly = preset("SizeSensor" to KppSensors.single("speed", "0,1;1,0.72;"))
        assertNull(KppXml.get(PresetPropertyMap.writeSpeedSize(speedOnly, 0.0), "SizeSensor"))
    }

    // ---------------------------------------------------------------- MASKING_SOFTNESS

    private val autoMaskDef = "<Brush scale=\"1\" type=\"auto_brush\" useAutoSpacing=\"0\" BrushVersion=\"2\" " +
        "spacing=\"0.1\" angle=\"0.0\" brushApplication=\"0\"> " +
        "<MaskGenerator diameter=\"20.0\" hfade=\"0.3\" vfade=\"0.3\" id=\"default\" spikes=\"2\" type=\"circle\" ratio=\"1.0\" antialiasEdges=\"1\"/> </Brush>"
    private val fileMaskDef = "<Brush scale=\"1\" type=\"png_brush\" useAutoSpacing=\"0\" BrushVersion=\"2\" " +
        "filename=\"tip.png\" spacing=\"0.1\" angle=\"0.0\" brushApplication=\"0\"/>"

    @Test
    fun maskingSoftness_autoBranch_usesMaskGeneratorFade() {
        val xml = preset(KppXml.MASK_BRUSH_DEF to autoMaskDef)
        assertEquals(0.3, PresetPropertyMap.readMaskingSoftness(xml)!!, 1e-9)
        val out = PresetPropertyMap.writeMaskingSoftness(xml, 0.6)
        assertEquals(0.6, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-9)
    }

    @Test
    fun maskingSoftness_fileBranch_usesSoftnessOption() {
        val xml = preset(KppXml.MASK_BRUSH_DEF to fileMaskDef)
        assertNull(PresetPropertyMap.readMaskingSoftness(xml))
        val out = PresetPropertyMap.writeMaskingSoftness(xml, 0.4)
        assertEquals("0.4", KppXml.get(out, "MaskingBrush/Preset/SoftnessValue"))
        assertEquals("true", KppXml.get(out, "MaskingBrush/Preset/PressureSoftness"))
        assertEquals("false", KppXml.get(out, "MaskingBrush/Preset/SoftnessUseCurve"))
        assertEquals(0.4, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-9)
    }

    @Test
    fun maskingSoftness_autoBranch_survivesGroupRewrite() {
        // 经 applyToXml 全路径（masking 组整组重写绕过 spec.write）：auto 笔尖上改柔度必须落盘
        val xml = preset("MaskingBrush/Enabled" to "true", KppXml.MASK_BRUSH_DEF to autoMaskDef)
        val eff = PresetPropertyMap.readParams(xml).copy(maskingSoftness = 0.55)
        val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.MASKING_SOFTNESS to 0.55), eff)
        assertEquals(0.55, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-6)
        // auto 笔尖柔度与渐隐同键（MaskGenerator hfade/vfade），读侧同源一致
        assertEquals(0.55, PresetPropertyMap.spec(ControlId.MASKING_FADE).read!!(out) as Double, 1e-6)
    }

    @Test
    fun maskingSoftness_fileBranch_survivesGroupRewrite() {
        val xml = preset("MaskingBrush/Enabled" to "true", KppXml.MASK_BRUSH_DEF to fileMaskDef)
        val eff = PresetPropertyMap.readParams(xml).copy(maskingSoftness = 0.55)
        val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.MASKING_SOFTNESS to 0.55), eff)
        assertEquals("0.55", KppXml.get(out, "MaskingBrush/Preset/SoftnessValue"))
        assertEquals(0.55, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-6)
    }

    @Test
    fun maskingSoftness_groupRewriteWithoutSoftnessCovered_keepsFade() {
        // 未覆盖 MASKING_SOFTNESS 时 auto 分支仍以 maskingFade 为准（旧语义不变）
        val xml = preset("MaskingBrush/Enabled" to "true", KppXml.MASK_BRUSH_DEF to autoMaskDef)
        val eff = PresetPropertyMap.readParams(xml).copy(maskingFade = 0.3, maskingSoftness = 0.8)
        val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.MASKING_FADE to 0.3), eff)
        assertEquals(0.3, PresetPropertyMap.spec(ControlId.MASKING_FADE).read!!(out) as Double, 1e-6)
    }

    @Test
    fun maskingSoftness_realAssetsRoundTrip() {
        // 用真实带副笔刷的资产验证 brush_definition 结构（R4）
        val withMask = KppFixtures.all.filter { KppXml.getBool(it.second, "MaskingBrush/Enabled", false) }
        assertTrue("应存在带副笔刷的内置资产", withMask.isNotEmpty())
        for ((name, xml) in withMask) {
            val def = KppXml.get(xml, KppXml.MASK_BRUSH_DEF)
            assertNotNull("$name 缺 MaskingBrush/Preset/brush_definition", def)
            assertTrue("$name 副笔刷定义不是 Brush 元素", KppXml.hasTag(def!!, "Brush"))
            val out = PresetPropertyMap.writeMaskingSoftness(xml, 0.42)
            assertEquals("$name", 0.42, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-6)
        }
    }

    @Test
    fun writeMasking_writesSkeletonKeys() {
        val eff = com.reverie.paint.core.BrushParams(maskingEnabled = true, maskingSoftness = 0.4)
        val out = PresetPropertyMap.writeMasking(preset(), eff)
        assertEquals("false", KppXml.get(out, "MaskingBrush/Preset/OpacityUseCurve"))
        assertEquals("false", KppXml.get(out, "MaskingBrush/Preset/FlowUseCurve"))
        assertEquals("paintbrush", KppXml.get(out, "MaskingBrush/Preset/paintop"))
    }

    // ---------------------------------------------------------------- MIX 极性（副色混色）

    private val mixSpec = PresetPropertyMap.spec(ControlId.MIX)

    @Test
    fun mix_constantWrite_flipsToForegroundWeight() {
        // 设备 kpp 形态（ABR 导入的旧产物）：PressureMix=true + MixUseCurve=false + MixValue=副色占比
        // 引擎把 MixValue 当前景权重消费 → 画白；App 侧读应取 1-MixValue，写回再翻转回来
        val xml = preset("PressureMix" to "true", "MixUseCurve" to "false", "MixValue" to "0.02")
        assertEquals(0.98, mixSpec.read!!(xml) as Double, 1e-9)
        val out = mixSpec.write!!(xml, 0.02, BrushParams())
        assertEquals("true", KppXml.get(out, "PressureMix"))
        assertEquals("原本 false 不动", "false", KppXml.get(out, "MixUseCurve"))
        assertEquals(0.98, KppXml.getDouble(out, "MixValue")!!, 1e-9)
        assertEquals(0.02, mixSpec.read!!(out) as Double, 1e-9)
    }

    @Test
    fun mix_freshPreset_constantWrite_landsUseCurveFalse() {
        // 全新预设（无任何 Mix 键）：常量混色必须落 MixUseCurve=false，否则引擎按曲线态走、常量不生效
        val out = mixSpec.write!!(preset(), 0.02, BrushParams())
        assertEquals("true", KppXml.get(out, "PressureMix"))
        assertEquals("false", KppXml.get(out, "MixUseCurve"))
        assertEquals(0.98, KppXml.getDouble(out, "MixValue")!!, 1e-9)
        assertEquals(0.02, mixSpec.read!!(out) as Double, 1e-9)
    }

    @Test
    fun mix_zeroWrite_unchecksOnly_neverTouchesMixValue() {
        // 关闭混色只取消勾选；MixValue 是引擎 ColorRate 的 legacy 回退键，绝不能抹
        val xml = preset("MixValue" to "0.7")
        val out = mixSpec.write!!(xml, 0.0, BrushParams())
        assertEquals("false", KppXml.get(out, "PressureMix"))
        assertEquals("0.7", KppXml.get(out, "MixValue"))
        assertNull(KppXml.get(out, "MixUseCurve"))
        assertEquals(0.0, mixSpec.read!!(out) as Double, 0.0)
    }

    @Test
    fun mix_nativeUncheckedPreset_identityWrite() {
        // 原生资产常态（Basic_circle 等 20 个内置 kpp）：PressureMix=false + MixUseCurve=true + MixValue=1
        val xml = preset("PressureMix" to "false", "MixUseCurve" to "true", "MixValue" to "1")
        assertEquals(0.0, mixSpec.read!!(xml) as Double, 0.0)
        val out = mixSpec.write!!(xml, mixSpec.read!!(xml) as Double, BrushParams())
        assertEquals("MixValue 保持原值", "1", KppXml.get(out, "MixValue"))
        assertEquals("false", KppXml.get(out, "PressureMix"))
        assertEquals(0.0, mixSpec.read!!(out) as Double, 0.0)
    }

    @Test
    fun mix_pressureColorMixOn_writesCurvePath() {
        // 压力混色开启：走 writeMixPressure 曲线路径；曲线 y 即前景权重（线性 0→1，不编码 secondaryMix）
        val out = mixSpec.write!!(preset(), 0.37, BrushParams(pressureColorMix = true))
        assertEquals("true", KppXml.get(out, "PressureMix"))
        assertEquals("true", KppXml.get(out, "MixUseCurve"))
        assertEquals(listOf("pressure"), KppSensors.sensorIds(KppSensors.sensorXml(out, "Mix")))
        assertEquals(0.0, mixSpec.read!!(out) as Double, 0.0)
    }

    // ---------------------------------------------------------------- 5C-2：Size 关闭不连坐子传感器

    @Test
    fun sizeDisable_keepsAliveSpeedSubSensor() {
        val sensor = "<!DOCTYPE params><params id=\"sensorslist\">" +
            "<ChildSensor id=\"pressure\"><curve>0,0;1,1;</curve></ChildSensor>" +
            "<ChildSensor id=\"speed\"><curve>0,1;1,0.72;</curve></ChildSensor></params>"
        val xml = preset(
            "SizeSensor" to sensor,
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
            "SizeValue" to "1.0",
        )
        val out = PresetPropertyMap.writeCurve(xml, "Size", CurveValue(false, "pressure", "0,0.2;1,0.8;", 0.7))
        assertEquals("UseCurve 保持开启，speed 继续生效", "true", KppXml.get(out, "SizeUseCurve"))
        assertEquals("true", KppXml.get(out, "PressureSize"))
        assertEquals("1.0", KppXml.get(out, "SizeValue"))
        val s = KppSensors.sensorXml(out, "Size")!!
        assertEquals("pressure 主传感器已移除", listOf("speed"), KppSensors.sensorIds(s))
        assertEquals("speed 曲线原样保留", "0,1;1,0.72;", KppSensors.sensorCurve(s, "speed"))
        // 关闭的曲线本体存 RawCurve，回读闭环
        val back = PresetPropertyMap.readCurve(out, "Size")!!
        assertEquals(false, back.enabled)
        assertEquals(CurveValue.normalizeCurve("0,0.2;1,0.8;"), back.curve)
    }

    @Test
    fun sizeDisable_pressureOnly_keepsLegacyUseCurveFalse() {
        val xml = preset(
            "SizeSensor" to KppSensors.single("pressure", "0,0.2;1,0.8;"),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
            "SizeValue" to "0.7",
        )
        val out = PresetPropertyMap.writeCurve(xml, "Size", CurveValue(false, "pressure", "0,0.2;1,0.8;", 0.7))
        assertEquals("false", KppXml.get(out, "SizeUseCurve"))
        assertEquals(listOf("pressure"), KppSensors.sensorIds(KppSensors.sensorXml(out, "Size")))
        assertEquals("关闭态不写 SizeValue", "0.7", KppXml.get(out, "SizeValue"))
    }

    @Test
    fun subSensorWrites_clearLegacySizeValue_whenSizeCurveOff() {
        val xml = preset("SizeValue" to "0.5")
        assertEquals("1.0", KppXml.get(PresetPropertyMap.writeJitterSize(xml, 0.3), "SizeValue"))
        assertEquals("1.0", KppXml.get(PresetPropertyMap.writeSpeedSize(xml, 0.4), "SizeValue"))
        // Size 曲线已生效时不覆写：SizeValue 归 CURVE_SIZE 的强度通道
        val on = preset(
            "SizeSensor" to KppSensors.single("pressure", KppSensors.LINEAR),
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
            "SizeValue" to "0.8",
        )
        assertEquals("0.8", KppXml.get(PresetPropertyMap.writeJitterSize(on, 0.3), "SizeValue"))
        assertEquals("0.8", KppXml.get(PresetPropertyMap.writeSpeedSize(on, 0.4), "SizeValue"))
    }

    // ---------------------------------------------------------------- SHARPNESS alignoutline

    @Test
    fun sharpness_writesAlignOutlineGate() {
        val spec = PresetPropertyMap.spec(ControlId.SHARPNESS)
        val on = spec.write!!(preset(), 0.3, BrushParams())
        assertEquals("true", KppXml.get(on, "Sharpness/alignoutline"))
        assertEquals("true", KppXml.get(on, "PressureSharpness"))
        assertEquals("0.3", KppXml.get(on, "SharpnessValue"))
        assertEquals(0.3, spec.read!!(on) as Double, 1e-9)
        val off = spec.write!!(preset(), 0.0, BrushParams())
        assertEquals("false", KppXml.get(off, "Sharpness/alignoutline"))
        assertEquals(0.0, spec.read!!(off) as Double, 0.0)
    }
}
