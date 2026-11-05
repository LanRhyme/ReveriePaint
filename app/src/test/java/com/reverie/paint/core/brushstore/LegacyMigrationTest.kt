/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import com.reverie.paint.core.LegacyBrushParamsCodec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * 旧数据迁移（执行文档 W-07），全部基于真实 kpp 资源：
 * - d8f2e258 合成的"重置"记录（K2）在 268 个真实 kpp 上全部被丢弃，有效文件逐字节回到原件；
 * - 重置后又真实编辑过（K3）只保留被改的控件；
 * - 内置（K0/K1）、分享导入（K4）、空导入（K5）、ABR（K6）、没有 "aut" 的旧记录、fork 的 ABR；
 * - 非内置文件导入 provenance（含 ReverieRotation 的标 possibly_modified）、备份轮转、幂等。
 * 旧记录统一经过 [LegacyBrushParamsCodec] 的写入→读回，与真机重启后的 brushParams 一致。
 */
class LegacyMigrationTest {
    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("migrate").toFile()
        BrushStoreBootstrap.resetForTest()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    private val stems: List<String> by lazy { KppFixtures.all.map { it.first } }

    /** 写入 prefs 再读回（与 persistBrushParams → loadBrushParams 相同） */
    private fun roundTrip(name: String, p: BrushParams): BrushParams =
        LegacyBrushParamsCodec.decode(JSONObject(LegacyBrushParamsCodec.encode(name, p).toString()))

    private fun xmlOf(bytes: ByteArray): String = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true)!!

    private class Run(val root: File, val presetDir: File, val report: LegacyBrushParamsMigrator.Report)

    /**
     * 按真机顺序跑一次启动：内置 [builtins] 作为出厂资源，[extra] 预先放在 paintoppresets/（旧版导入/ABR 产物），
     * migrate 回调里执行迁移。
     */
    private fun boot(
        root: File,
        builtins: List<String>,
        extra: Map<String, ByteArray>,
        legacy: Map<String, BrushParams>,
        ts: String = "20261023-120000",
    ): Run {
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        for ((file, b) in extra) File(presetDir, file).writeBytes(b)
        val assets = builtins.associate { "$it.kpp" to KppFixtures.bytes(it) }
        val raw = org.json.JSONArray().apply { legacy.forEach { (n, p) -> put(LegacyBrushParamsCodec.encode(n, p)) } }.toString()
        var report: LegacyBrushParamsMigrator.Report? = null
        BrushStoreBootstrap.resetForTest()
        assertTrue(
            BrushStoreBootstrap.run(root, presetDir, true, "1", { assets }, migrate = {
                report = LegacyBrushParamsMigrator.run(root, presetDir, legacy, raw, ts)
                true
            }),
        )
        return Run(root, presetDir, report!!)
    }

    @Test
    fun `K2 全部真实 kpp 的 d8f2e258 合成记录被丢弃且有效文件回到原件`() {
        val extra = stems.associate { "ext_$it.kpp" to KppFixtures.bytes(it) }
        val legacy = stems.associate { s ->
            val name = "ext_$s"
            name to roundTrip(name, LegacyBrushParamsMigrator.synthesizeD8f2e258(xmlOf(KppFixtures.bytes(s)), ""))
        }
        val r = boot(File(tmp, "k2"), stems.take(2), extra, legacy)
        assertEquals(268, stems.size)
        for (s in stems) {
            val name = "ext_$s"
            assertEquals(name, LegacyBrushParamsMigrator.Kind.K2_RESET_UNEDITED, r.report.kindOf(name))
            val id = BrushIdentity.idForName(name)!!
            assertTrue(name, BrushOverrideStore.get(id).isEmpty())
            assertArrayEquals(name, KppFixtures.bytes(s), File(r.presetDir, "$name.kpp").readBytes())
        }
    }

    @Test
    fun `K3 重置后真实编辑只保留被改的控件`() {
        val extra = stems.associate { "ext_$it.kpp" to KppFixtures.bytes(it) }
        val curve = CurveValue(true, "pressure", "0,0;0.3,0.7;1,1;", 1.0).encode()
        val expected = HashMap<String, Set<String>>()
        val legacy = HashMap<String, BrushParams>()
        for (s in stems) {
            val name = "ext_$s"
            val xml = xmlOf(KppFixtures.bytes(s))
            val ctx = PresetContext.of(xml)
            val synth = roundTrip(name, LegacyBrushParamsMigrator.synthesizeD8f2e258(xml, ""))
            var rec = synth.copy(size = synth.size + 5.0)
            val keys = linkedSetOf(ControlId.SIZE.key)
            val cs = PresetPropertyMap.spec(ControlId.CURVE_OPACITY)
            if (cs.applicable(ctx) && cs.write != null) {
                // 旧工作台保存曲线时写入全量 dynamicOptions 并置 dc
                val snapP = PresetReadback.fromXml(xml)
                val base = rec.copy(dynamicsCustomized = true, dynamicOptions = snapP.dynamicOptions)
                val cur = CurveValue.parse(cs.get(snapP) as? String)
                val c = CurveValue.parse(curve)!!.let { if (cur != null) it.copy(strength = cur.strength) else it }.encode()
                if (!PresetPropertyMap.same(ControlId.CURVE_OPACITY, cs.get(snapP), c)) {
                    rec = PresetPropertyMap.deriveFields(cs.set(base, c))
                    keys += ControlId.CURVE_OPACITY.key
                }
            }
            legacy[name] = roundTrip(name, rec)
            expected[name] = keys
        }
        val r = boot(File(tmp, "k3"), stems.take(2), extra, legacy)
        var withCurve = 0
        for (s in stems) {
            val name = "ext_$s"
            assertEquals(name, LegacyBrushParamsMigrator.Kind.K3_RESET_THEN_EDITED, r.report.kindOf(name))
            val id = BrushIdentity.idForName(name)!!
            assertEquals(name, expected.getValue(name), BrushOverrideStore.get(id).controls.keys)
            // SIZE 是实时控件（不写进 kpp，由 BrushApply 下发），看覆盖值；曲线写进有效 kpp，看回读
            val o = BrushOverrideStore.get(id)
            assertEquals(name, legacy.getValue(name).size, (o.controls.getValue(ControlId.SIZE.key) as Number).toDouble(), 1e-3)
            if (ControlId.CURVE_OPACITY.key in expected.getValue(name)) {
                withCurve++
                val effXml = xmlOf(File(r.presetDir, "$name.kpp").readBytes())
                val cs = PresetPropertyMap.spec(ControlId.CURVE_OPACITY)
                assertTrue(name, PresetPropertyMap.same(ControlId.CURVE_OPACITY, cs.read!!(effXml), cs.get(legacy.getValue(name))))
            }
        }
        assertTrue("曲线编辑样本太少: $withCurve", withCurve >= 50)
    }

    @Test
    fun `各类旧记录分类与导入来源`() {
        val b1 = stems[0]
        val b2 = stems[1]
        val share = stems[10]
        val empty = stems[20]
        val abr = stems[30]
        val abrEdited = stems[40]
        val oldNoAut = stems[50]
        val forkAbr = stems[60]
        val rewritten = stems[70]
        val builtins = listOf(b1, b2)
        val extra = LinkedHashMap<String, ByteArray>()
        listOf(share, empty, abr, abrEdited, oldNoAut, forkAbr).forEach { extra["x_$it.kpp"] = KppFixtures.bytes(it) }
        // 旧版 updateParam 改写过的 Krita 原件：ReverieRotation 追加在文件尾
        val rw = KppFixtures.bytes(rewritten)
        val rwXml = KppHelper.readPresetXml(rw)!!.replace(
            "</Preset>", " <param type=\"string\" name=\"ReverieRotation\"><![CDATA[15.0]]></param>\n</Preset>",
        )
        extra["x_$rewritten.kpp"] = KppHelper.replacePresetXml(rw, rwXml)
        extra["小笔.myb"] = "{}".toByteArray()

        fun snapP(stem: String) = PresetReadback.fromXml(xmlOf(KppFixtures.bytes(stem)))
        val legacy = LinkedHashMap<String, BrushParams>()
        legacy[b1] = snapP(b1).copy(opacity = 0.5, isCustomized = false) // 内置未自定义 → K0
        legacy[b2] = snapP(b2).copy(opacity = 0.37, isCustomized = true) // 内置改过 → K1
        legacy["x_$share"] = snapP(share).copy(
            size = snapP(share).size + 9.0, isCustomized = true, author = "外部创作者 (分享)", isAuthorLocked = true,
        )
        legacy["x_$empty"] = snapP(empty).copy(isCustomized = false, author = "外部创作者 (分享)", isAuthorLocked = true)
        legacy["x_$abr"] = snapP(abr).copy(isCustomized = true, author = LegacyBrushParamsMigrator.AUTHOR_ABR, isAuthorLocked = true)
        legacy["x_$abrEdited"] = snapP(abrEdited).copy(
            opacity = 0.21, isCustomized = true, author = LegacyBrushParamsMigrator.AUTHOR_ABR, isAuthorLocked = true,
        )
        legacy["x_$forkAbr"] = snapP(forkAbr).copy(isCustomized = false, author = LegacyBrushParamsMigrator.AUTHOR_ABR, isAuthorLocked = true)
        legacy["小笔"] = BrushParams(isCustomized = true)
        legacy["已删除的笔"] = BrushParams(isCustomized = true)
        val enc = LinkedHashMap<String, BrushParams>()
        legacy.forEach { (n, p) -> enc[n] = roundTrip(n, p) }
        // 没有 "aut" 字段的旧记录：读回后作者为默认值且未锁定，指纹 S1 成立，再与合成值逐控件比较
        val noAut = LegacyBrushParamsCodec.encode("x_$oldNoAut", snapP(oldNoAut).copy(size = 3.0, isCustomized = true))
        noAut.remove("aut")
        noAut.remove("autl")
        enc["x_$oldNoAut"] = LegacyBrushParamsCodec.decode(JSONObject(noAut.toString()))

        val r = boot(File(tmp, "mix"), builtins, extra, enc)
        assertEquals(LegacyBrushParamsMigrator.Kind.K0_BUILTIN_PLAIN, r.report.kindOf(b1))
        assertEquals(LegacyBrushParamsMigrator.Kind.K1_BUILTIN_EDITED, r.report.kindOf(b2))
        assertEquals(setOf(ControlId.OPACITY.key), r.report.controlsOf(b2))
        assertEquals(LegacyBrushParamsMigrator.Kind.K4_EDITED, r.report.kindOf("x_$share"))
        assertEquals(setOf(ControlId.SIZE.key, "author", "isAuthorLocked"), r.report.controlsOf("x_$share"))
        assertEquals(LegacyBrushParamsMigrator.Kind.K5_IMPORT_EMPTY, r.report.kindOf("x_$empty"))
        assertEquals(LegacyBrushParamsMigrator.Kind.K6_ABR, r.report.kindOf("x_$abr"))
        assertEquals(setOf("author", "isAuthorLocked"), r.report.controlsOf("x_$abr"))
        assertEquals(setOf(ControlId.OPACITY.key, "author", "isAuthorLocked"), r.report.controlsOf("x_$abrEdited"))
        assertEquals(LegacyBrushParamsMigrator.Kind.K5_IMPORT_EMPTY, r.report.kindOf("x_$forkAbr"))
        assertEquals(LegacyBrushParamsMigrator.Kind.K3_RESET_THEN_EDITED, r.report.kindOf("x_$oldNoAut"))
        assertTrue(ControlId.SIZE.key in r.report.controlsOf("x_$oldNoAut"))
        assertEquals(LegacyBrushParamsMigrator.Kind.UNMANAGED, r.report.kindOf("小笔"))
        assertEquals(LegacyBrushParamsMigrator.Kind.UNMANAGED, r.report.kindOf("已删除的笔"))

        // 内置：K1 的覆盖生效，K0 有效文件 == 出厂
        val eff2 = PresetReadback.fromEffectiveBytes(File(r.presetDir, "$b2.kpp").readBytes())
        assertEquals(0.37, eff2.opacity, 1e-6)
        assertArrayEquals(KppFixtures.bytes(b1), File(r.presetDir, "$b1.kpp").readBytes())

        // 导入来源
        assertEquals(LegacyBrushParamsMigrator.PROVENANCE_POSSIBLY_MODIFIED, r.report.adopted["x_$rewritten"])
        assertEquals(LegacyBrushParamsMigrator.PROVENANCE_MIGRATED, r.report.adopted["x_$share"])
        assertEquals(LegacyBrushParamsMigrator.PROVENANCE_MIGRATED, r.report.adopted["x_$abr"])
        assertEquals(LegacyBrushParamsMigrator.PROVENANCE_MIGRATED, r.report.adopted["小笔"])
        assertEquals(8, r.report.adopted.size) // 7 个 kpp + 小笔.myb；内置不导入
        val id = BrushIdentity.idForName("x_$rewritten")!!
        assertEquals(LegacyBrushParamsMigrator.PROVENANCE_POSSIBLY_MODIFIED, PresetSnapshotStore.source(id)?.optString("provenance"))
        assertEquals(BrushIdentity.Kind.IMPORTED, BrushIdentity.entryForName("x_$share")!!.kind)
    }

    @Test
    fun `App 模板生成的 kpp 不算原件被改写`() {
        val xml = xmlOf(KppFixtures.bytes(stems[0]))
        assertTrue(!LegacyBrushParamsMigrator.isPossiblyModified(xml))
        val appended = xml.replace("</Preset>", " <param type=\"string\" name=\"ReverieRotation\"><![CDATA[0]]></param>\n</Preset>")
        assertTrue(LegacyBrushParamsMigrator.isPossiblyModified(appended))
        assertTrue(!LegacyBrushParamsMigrator.isPossiblyModified(appended, LegacyBrushParamsMigrator.AUTHOR_ABR))
        val template = """<Preset name="a" paintopid="paintbrush">
  <param type="string" name="RotationValue"><![CDATA[1]]></param>
  <param type="string" name="ReverieRotation"><![CDATA[0.0]]></param>
</Preset>"""
        assertTrue(!LegacyBrushParamsMigrator.isPossiblyModified(template))
    }

    @Test
    fun `备份只保留最近两份且迁移可重复执行`() {
        val s = stems[5]
        val extra = mapOf("x_$s.kpp" to KppFixtures.bytes(s))
        val sp = PresetReadback.fromXml(xmlOf(KppFixtures.bytes(s)))
        val legacy = mapOf(
            "x_$s" to roundTrip("x_$s", sp.copy(flow = 0.42, isCustomized = true, author = "外部创作者 (分享)", isAuthorLocked = true)),
        )
        val root = File(tmp, "idem")
        val r1 = boot(root, stems.take(1), extra, legacy, "20261020-000000")
        val id = BrushIdentity.idForName("x_$s")!!
        val o1 = BrushOverrideStore.get(id)
        assertEquals(setOf(ControlId.FLOW.key, "author", "isAuthorLocked"), o1.controls.keys)
        assertNotNull(r1.report.backupDir)
        assertTrue(File(r1.report.backupDir, "paintoppresets/x_$s.kpp").isFile)
        assertTrue(File(r1.report.backupDir, "brush_params.json").readText().contains("x_$s"))

        // 第二、三次（例如迁移标记未写成功）：不重复导入，覆盖不变，备份轮转
        val r2 = boot(root, stems.take(1), emptyMap(), legacy, "20261021-000000")
        val r3 = boot(root, stems.take(1), emptyMap(), legacy, "20261022-000000")
        assertTrue(r2.report.adopted.isEmpty())
        assertTrue(r3.report.adopted.isEmpty())
        assertEquals(id, BrushIdentity.idForName("x_$s"))
        assertEquals(o1.controls.keys, BrushOverrideStore.get(id).controls.keys)
        assertEquals(1, BrushIdentity.all().count { it.name == "x_$s" })
        val dirs = File(root, LegacyBrushParamsMigrator.BACKUP_DIR).list()!!.sorted()
        assertEquals(listOf("20261021-000000", "20261022-000000"), dirs)
    }

    @Test
    fun `全量迁移 schema 0 到 3 APP_ONLY 规则验证`() {
        val b0 = stems[0] // factory K0
        val b1 = stems[1] // factory K1
        val imported = stems[2]
        val builtins = listOf(b0, b1)
        val extra = mapOf("x_$imported.kpp" to KppFixtures.bytes(imported))

        fun snapP(stem: String) = PresetReadback.fromXml(xmlOf(KppFixtures.bytes(stem)))
        val legacy = LinkedHashMap<String, BrushParams>()
        // K0 出厂笔：带 author="Krita", isAuthorLocked=true，不应进 controls
        legacy[b0] = snapP(b0).copy(author = "Krita", isAuthorLocked = true, isCustomized = false)
        // K1 出厂笔：改了 opacity，且带 author="Krita", isAuthorLocked=true，author/locked 不应进 controls
        legacy[b1] = snapP(b1).copy(opacity = 0.35, author = "Krita", isAuthorLocked = true, isCustomized = true)
        // 非出厂笔：streamline=0.6（与 baseline 默认 0.0 不同），taper 保持默认（与 baseline 相同不写），author="外部创作者 (ABR)"
        legacy["x_$imported"] = snapP(imported).copy(
            streamline = 0.6,
            author = LegacyBrushParamsMigrator.AUTHOR_ABR,
            isAuthorLocked = true,
            isCustomized = true,
        )

        val r = boot(File(tmp, "full_0to3"), builtins, extra, legacy)
        val idB0 = BrushIdentity.idForName(b0)!!
        val idB1 = BrushIdentity.idForName(b1)!!
        val idImp = BrushIdentity.idForName("x_$imported")!!

        // 出厂笔 author/isAuthorLocked 不进 controls
        val oB0 = BrushOverrideStore.get(idB0)
        assertFalse("author" in oB0.controls)
        assertFalse("isAuthorLocked" in oB0.controls)

        val oB1 = BrushOverrideStore.get(idB1)
        assertEquals(0.35, (oB1.controls[ControlId.OPACITY.key] as Number).toDouble(), 1e-6)
        assertFalse("author" in oB1.controls)
        assertFalse("isAuthorLocked" in oB1.controls)

        // 非出厂笔：streamline=0.6 写入，与 baseline 相同的 taper 不写，author="外部创作者 (ABR)" 写入
        val oImp = BrushOverrideStore.get(idImp)
        assertEquals(0.6, (oImp.controls["streamline"] as Number).toDouble(), 1e-6)
        assertFalse("taper" in oImp.controls)
        assertEquals(LegacyBrushParamsMigrator.AUTHOR_ABR, oImp.controls["author"])
        assertEquals(true, oImp.controls["isAuthorLocked"])
    }

    @Test
    fun `增量迁移 2 到 3 保留已有覆盖并追加 APP_ONLY 且不重导磁盘文件`() {
        val s = stems[3]
        val extra = mapOf("x_$s.kpp" to KppFixtures.bytes(s))
        val sp = PresetReadback.fromXml(xmlOf(KppFixtures.bytes(s)))
        val name = "x_$s"
        val root = File(tmp, "incr_2to3")
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        extra.forEach { (file, b) -> File(presetDir, file).writeBytes(b) }
        val assets = mapOf<String, ByteArray>()

        // 模拟已存在 schema 2 的状态：已注册 identity，overrides 中已有 kpp 控件 + rawKeys + dirty
        BrushStoreBootstrap.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { assets }, migrate = { true }))
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(id, "kpp", KppFixtures.bytes(s), JSONObject().put("provenance", "migrated"))
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED))

        val initialOverrides = BrushOverrideStore.Overrides(
            controls = mapOf("spacing" to 0.15),
            dirty = setOf("spacing", "unknownControl"),
            rawKeys = mapOf("CustomRawKey" to "CustomRawValue"),
        )
        BrushOverrideStore.replace(id, initialOverrides)

        // 磁盘上放一个新文件，测试 fromSchema=2 时不会调 adoptDiskPresets
        File(presetDir, "should_not_adopt.kpp").writeBytes(KppFixtures.bytes(stems[4]))

        val legacy = mapOf(
            name to sp.copy(streamline = 0.7, taper = 0.2, author = "某个作者", isAuthorLocked = true),
        )
        val raw = org.json.JSONArray().apply { legacy.forEach { (n, p) -> put(LegacyBrushParamsCodec.encode(n, p)) } }.toString()

        val report = LegacyBrushParamsMigrator.run(
            storeRoot = root,
            presetDir = presetDir,
            legacy = legacy,
            legacyRaw = raw,
            timestamp = "20261026-120000",
            fromSchema = 2,
        )

        // 不重新导入磁盘文件
        assertTrue("adopted 应为空", report.adopted.isEmpty())
        assertNull(BrushIdentity.entryForName("should_not_adopt"))

        // 已有 overrides（spacing, rawKeys, dirty）原样保留，只加上 APP_ONLY 控件
        val finalOverrides = BrushOverrideStore.get(id)
        assertEquals(0.15, (finalOverrides.controls["spacing"] as Number).toDouble(), 1e-6)
        assertEquals(0.7, (finalOverrides.controls["streamline"] as Number).toDouble(), 1e-6)
        assertEquals(0.2, (finalOverrides.controls["taper"] as Number).toDouble(), 1e-6)
        assertEquals("某个作者", finalOverrides.controls["author"])
        assertEquals(true, finalOverrides.controls["isAuthorLocked"])
        assertEquals(initialOverrides.rawKeys, finalOverrides.rawKeys)
        assertEquals(initialOverrides.dirty, finalOverrides.dirty)
        assertEquals(LegacyBrushParamsMigrator.Kind.APP_ONLY_2TO3, report.kindOf(name))
    }
}
