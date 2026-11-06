/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrParser
import com.reverie.paint.core.AbrPresetConverter
import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 转换器版本漂移自愈（Round3-B）：启动时 [BrushStoreBootstrap.refreshDriftedAbrSnapshots] 把
 * provenance=abr 且 abrConverterVersion < 当前版本的导入快照按 abrParams（旧版缺字段从旧快照 XML
 * 回填）重转，更新 source.json；当前版本零重写；损坏条目跳过不拖垮启动；colorsmudge 不退化。
 */
class AbrConverterDriftBootstrapTest {
    private lateinit var root: File
    private lateinit var presetDir: File

    /** v13 起新增的 abrParamsJson 键：模拟旧版（v11）source.json 时剥掉 */
    private val V13_TOP_KEYS = listOf(
        "angleControl", "flipXJitter", "flipYJitter", "scatterPressure", "scatterCount",
        "opacityMin", "flowMin", "toolClass", "smudgeStrength", "wetness", "mix", "dryness",
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("drift").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        boot()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private val builtinStem = KppFixtures.all.first().first

    private fun boot() {
        BrushStoreBootstrap.resetForTest()
        assertTrue(
            BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$builtinStem.kpp" to KppFixtures.bytes(builtinStem)) }, migrate = { true }),
        )
    }

    private fun effectiveFile(name: String) = File(presetDir, "$name.kpp")

    private fun xmlOf(bytes: ByteArray) = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true)!!

    /** 模拟旧版转换入库：v13 转换 → 打补丁成旧字节 + v11 格式 source.json（剥掉 v13 键） */
    private fun makeV11(
        name: String,
        preset: AbrParser.AbrPresetInfo,
        oldXmlPatch: (String) -> String = { it },
        sourcePatch: (JSONObject) -> Unit = {},
        policy: com.reverie.paint.core.AbrBlendModes.Policy = com.reverie.paint.core.AbrBlendModes.Policy.COMPAT_SAFE,
        recordPolicy: String? = null,
        patternRefs: Map<String, ResourceStore.Ref> = emptyMap(),
    ): Pair<String, ByteArray> {
        val conv = AbrPresetConverter.convert(preset, name, null, "", emptyMap(), "v11pack.abr", patternRefs = patternRefs, policy = policy)
        val patchedXml = oldXmlPatch(xmlOf(conv.kppBytes))
        val bytes = KppHelper.replacePresetXml(conv.kppBytes, patchedXml)
        val abrParams = AbrPresetConverter.abrParamsJson(preset)
        for (k in V13_TOP_KEYS) abrParams.remove(k)
        abrParams.optJSONObject("dualBrush")?.let { d -> for (k in listOf("rawMode", "scatterBothAxes", "count", "angle", "roundness")) d.remove(k) }
        abrParams.optJSONObject("texture")?.let { t -> for (k in listOf("rawMode", "brightness", "contrast", "depthPressure", "depthMin")) t.remove(k) }
        abrParams.optJSONObject("colorDynamics")?.remove("fgBgControl")
        val source = JSONObject()
            .put("provenance", BrushImportPipeline.PROVENANCE_ABR)
            .put("pack", "v11pack.abr")
            .put("file", "$name.kpp")
            .put("md5", BrushDiagnostics.md5(bytes))
            .put("abrConverterVersion", 11)
            .put("abrParams", abrParams)
            .put("appBaseline", PresetReadback.baselineJson(conv.bp))
        if (recordPolicy != null) source.put("abrBlendPolicy", recordPolicy)
        sourcePatch(source)
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(id, "kpp", bytes, source)
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, "v11pack.abr"))
        EffectivePresetBuilder.materialize(id, presetDir)
        return id to bytes
    }

    @Test
    fun driftedV11SnapshotIsRebuiltAndKeepsUserOverride() {
        // v11 旧语义：clVr.jitter 被误映射成常量混色（PressureMix=true + MixValue=0.02）
        val preset = AbrParser.AbrPresetInfo(
            name = "V11Mix",
            colorDynamics = AbrParser.AbrColorDynamicsInfo(hueJitter = 0.2, secondaryMix = 0.02, fgBgControl = 0),
        )
        val (id, oldBytes) = makeV11(preset.name, preset, oldXmlPatch = { xml ->
            KppXml.set(KppXml.set(xml, "PressureMix", "true"), "MixValue", "0.02")
        })
        assertTrue("旧快照确实带着旧混色写法", xmlOf(oldBytes).contains("""name="PressureMix"><![CDATA[true]]>"""))
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.45), dirty = setOf("opacity")))
        EffectivePresetBuilder.materialize(id, presetDir)
        val oldSnapshotMd5 = BrushDiagnostics.md5(oldBytes)

        boot()

        val src = PresetSnapshotStore.source(id)!!
        assertEquals(13, src.getInt("abrConverterVersion"))
        assertEquals(BrushDiagnostics.md5(PresetSnapshotStore.read(id)!!), src.getString("md5"))
        val newSnap = PresetSnapshotStore.read(id)!!
        val newXml = xmlOf(newSnap)
        assertFalse("刷新后不得再写 PressureMix=true", newXml.contains("""name="PressureMix"><![CDATA[true]]>"""))
        assertTrue("快照字节应被重写", oldSnapshotMd5 != BrushDiagnostics.md5(newSnap))
        // 用户覆盖仍在生效文件里（materializeAll 按新快照重新叠加）
        val eff = PresetReadback.fromEffectiveBytes(effectiveFile(preset.name).readBytes())
        assertEquals(0.45, eff.opacity, 1e-6)
        // 重置 → 回干净新快照
        BrushEditSession.reset(id, presetDir)
        assertArrayEquals("重置后 == 新快照", newSnap, effectiveFile(preset.name).readBytes())
        assertTrue(BrushOverrideStore.get(id).isEmpty())
    }

    @Test
    fun currentVersionSnapshotIsNotRewrittenAtAll() {
        val preset = AbrParser.AbrPresetInfo(name = "V13Current", pressureSize = true)
        val conv = AbrPresetConverter.convert(preset, preset.name, null, "", emptyMap(), "fresh.abr")
        val id = BrushImportPipeline.importAbr(
            preset.name, conv.kppBytes, AbrPresetConverter.abrParamsJson(preset),
            AbrPresetConverter.CONVERTER_VERSION, "fresh.abr", presetDir, PresetReadback.baselineJson(conv.bp),
        )
        val snapBefore = PresetSnapshotStore.read(id)!!
        val sourceBefore = PresetSnapshotStore.sourceFile(id)!!.readBytes()
        val effBefore = effectiveFile(preset.name).readBytes()

        boot()

        assertArrayEquals("快照零重写", snapBefore, PresetSnapshotStore.read(id))
        assertArrayEquals("source.json 零重写", sourceBefore, PresetSnapshotStore.sourceFile(id)!!.readBytes())
        assertArrayEquals("生效文件零重写", effBefore, effectiveFile(preset.name).readBytes())
    }

    @Test
    fun damagedEntriesAreSkippedAndRunStillSucceeds() {
        // 损坏一：source.json 非法 JSON
        val (corruptId, _) = makeV11("CorruptSource", AbrParser.AbrPresetInfo(name = "CorruptSource"))
        val corruptFile = PresetSnapshotStore.sourceFile(corruptId)!!
        corruptFile.setWritable(true)
        corruptFile.writeText("{not json")
        // 损坏二：provenance=abr 但缺 abrParams
        val (missingId, missingBytes) = makeV11(
            "NoParams",
            AbrParser.AbrPresetInfo(name = "NoParams", pressureSize = true),
            sourcePatch = { it.remove("abrParams") },
        )

        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$builtinStem.kpp" to KppFixtures.bytes(builtinStem)) }, migrate = { true }))
        assertFalse("损坏条目不得触发全局只读", BrushStoreBootstrap.readOnly)
        // 缺 abrParams 的按损坏跳过：字节原样
        assertArrayEquals(missingBytes, PresetSnapshotStore.read(missingId))
        assertEquals(11, PresetSnapshotStore.source(missingId)!!.getInt("abrConverterVersion"))
        // 其余预设正常物化（内置 + 正常导入）
        assertTrue(effectiveFile(builtinStem).isFile)
        assertTrue(effectiveFile("CorruptSource").isFile)
    }

    @Test
    fun colorSmudgeDoesNotDegradeWhenOldParamsLackToolClass() {
        // v11 时代 abrParams 没记 toolClass/smudge 参数；旧快照 XML 本身是 colorsmudge。
        // 重建时必须从旧 XML 回填，否则涂抹笔退化成 paintbrush（33/34 案例）
        val preset = AbrParser.AbrPresetInfo(
            name = "SmudgeV11",
            toolClass = "SmTl",
            smudgeStrength = 0.7,
            pressureFlow = true,
        )
        val (id, oldBytes) = makeV11(preset.name, preset)
        assertEquals("colorsmudge", KppXml.paintOpId(xmlOf(oldBytes)))

        boot()

        val newSnap = PresetSnapshotStore.read(id)!!
        val newXml = xmlOf(newSnap)
        assertEquals("paintop 不得退化", "colorsmudge", KppXml.paintOpId(newXml))
        assertEquals("涂抹强度不丢", "0.7", KppXml.get(newXml, "SmudgeRateValue"))
        assertEquals("仍是涂抹而非混合器", "0", KppXml.get(newXml, "SmudgeRateMode") ?: "0")
        val src = PresetSnapshotStore.source(id)!!
        assertEquals(13, src.getInt("abrConverterVersion"))
        assertEquals("SmTl", src.getJSONObject("abrParams").getString("toolClass"))
        // 重建结果应与“回填后的 preset 走一遍 v13 全新转换”逐字节一致（XML 部分）
        val restored = AbrPresetConverter.fromAbrParamsJson(src.getJSONObject("abrParams"))
        val fresh = AbrPresetConverter.convert(restored, preset.name, null, "", emptyMap(), "v11pack.abr")
        assertEquals(xmlOf(fresh.kppBytes), newXml)
    }

    @Test
    fun nativeBlendPolicySurvivesRefresh() {
        // 修复 2：NATIVE（PRECISION）导入的双重画笔 linear_burn 不被刷新静默改回 COMPAT_SAFE 的回退值
        val preset = AbrParser.AbrPresetInfo(
            name = "NativePolicy",
            diameter = 40.0,
            dualBrush = AbrParser.AbrDualBrushInfo(diameter = 20.0, compositeOp = "linear_burn"),
        )
        val (id, oldBytes) = makeV11(preset.name, preset, policy = com.reverie.paint.core.AbrBlendModes.Policy.PRECISION, recordPolicy = "PRECISION")
        val oldOp = KppXml.get(xmlOf(oldBytes), "MaskingBrush/MaskingCompositeOp")
        assertEquals("linear_burn", oldOp)

        boot()

        val src = PresetSnapshotStore.source(id)!!
        assertEquals(13, src.getInt("abrConverterVersion"))
        assertEquals("策略键必须原样保留", "PRECISION", src.optString("abrBlendPolicy"))
        assertEquals(
            "刷新后不得回退到 COMPAT_SAFE 映射",
            "linear_burn",
            KppXml.get(xmlOf(PresetSnapshotStore.read(id)!!), "MaskingBrush/MaskingCompositeOp"),
        )
    }

    @Test
    fun v11FieldsWithoutJsonKeysAreBackfilledFromOldXml() {
        // 修复 1：v11/v12 abrParams 没记、但 convert 已消费的字段，从旧快照 XML 回填
        val preset = AbrParser.AbrPresetInfo(
            name = "V11Backfill",
            diameter = 50.0,
            spacing = 0.2,
            pressureOpacity = true,
            pressureFlow = true,
            opacityJitter = 0.1,
            flowJitter = 0.1,
            opacityMin = 0.3,
            flowMin = 0.25,
            flipXJitter = true,
            scatter = 0.4,
            scatterPressure = true,
            dualBrush = AbrParser.AbrDualBrushInfo(diameter = 25.0, angle = 30.0),
            texture = AbrParser.AbrTextureInfo(
                patternName = "P", patternUuid = "p-1", brightness = 51, contrast = -50, depth = 0.4,
                depthPressure = true, depthMin = 0.2,
            ),
        )
        val ref = ResourceStore.Ref(md5 = "testmd5", file = "abr_pat_test.png", kind = ResourceStore.Kind.PATTERN)
        val (id, _) = makeV11(preset.name, preset, patternRefs = mapOf("p-1" to ref))

        boot()

        val ap = PresetSnapshotStore.source(id)!!.getJSONObject("abrParams")
        assertEquals("dualBrush.angle 从蒙版定义弧度回填", 30.0, ap.getJSONObject("dualBrush").getDouble("angle"), 1e-4)
        assertEquals("texture.brightness 从 Krita 域反推", 51, ap.getJSONObject("texture").getInt("brightness"))
        assertEquals("texture.contrast 从 Krita 域反推", -50, ap.getJSONObject("texture").getInt("contrast"))
        assertTrue("depthPressure 从 Texture/Strength 压力曲线回填", ap.getJSONObject("texture").getBoolean("depthPressure"))
        assertEquals("depthMin 从曲线起点回填", 0.2, ap.getJSONObject("texture").getDouble("depthMin"), 1e-3)
        assertTrue("flipXJitter 从 Mirror 选项回填", ap.getBoolean("flipXJitter"))
        assertTrue("scatterPressure 从 Scatter 传感器回填", ap.getBoolean("scatterPressure"))
        assertEquals("opacityMin 从 Opacity 曲线起点回填", 0.3, ap.getDouble("opacityMin"), 1e-3)
        assertEquals("flowMin 从 Flow 曲线起点回填", 0.25, ap.getDouble("flowMin"), 1e-3)

        val newXml = xmlOf(PresetSnapshotStore.read(id)!!)
        assertEquals("蒙版角度保持", "0.5235987755982988", KppXml.get(newXml, KppXml.MASK_BRUSH_DEF)?.let { KppXml.tagAttr(it, "Brush", "angle") })
        assertEquals("纹理绑定保持", "abr_pat_test.png", KppXml.get(newXml, "Texture/Pattern/PatternFileName"))
        assertEquals("纹理 md5 保持", "testmd5", KppXml.get(newXml, "Texture/Pattern/PatternMD5Sum"))
        assertEquals("纹理亮度保持", "-0.2", KppXml.get(newXml, "Texture/Pattern/Brightness"))
        assertEquals("纹理对比度保持", "0.5", KppXml.get(newXml, "Texture/Pattern/Contrast"))
        assertEquals("不透明度曲线下限保持", "0,0.300;1,1;", KppSensors.sensorCurve(KppSensors.sensorXml(newXml, "Opacity"), "pressure"))
        assertEquals("流量曲线下限保持", "0,0.250;1,1;", KppSensors.sensorCurve(KppSensors.sensorXml(newXml, "Flow"), "pressure"))
    }
}
