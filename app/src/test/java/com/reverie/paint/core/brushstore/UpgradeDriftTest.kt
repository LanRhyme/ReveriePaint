/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 内置升级（执行文档 W-10）。"旧版 APK 资产"与"新版 APK 资产"都由真实内置 kpp 拼成：
 * 变更 = 同名文件换成另一个真实 kpp 的字节（同 paintop / 不同 paintop 两种），删除 = 新清单里没有，新增 = 旧清单里没有。
 * 期望：无覆盖 == 新 asset；有覆盖 == build(新 asset, 覆盖)；重置 == 新 asset；删除的内置变成"已下线"导入快照、覆盖保留。
 */
class UpgradeDriftTest {
    private lateinit var root: File
    private lateinit var presetDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("upgrade").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun op(stem: String) = KppXml.paintOpId(KppFixtures.xml(stem))

    private fun boot(stamp: String, assets: Map<String, ByteArray>) {
        BrushStoreBootstrap.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, stamp, { assets }, migrate = { true }))
    }

    private fun eff(name: String) = File(presetDir, "$name.kpp").readBytes()

    @Test
    fun upgradeAppliesNewSnapshotsKeepsOverridesAndRetiresRemoved() {
        val stems = KppFixtures.all.map { it.first }
        val byOp = stems.groupBy { op(it) }
        val sameOp = byOp.values.first { it.size >= 4 }
        val (plain, overridden, donorSame, removed) = sameOp.take(4)
        val otherOpDonor = stems.first { op(it) != op(overridden) }
        val opChanged = sameOp.getOrNull(4) ?: byOp.values.first { it.size >= 2 && it !== sameOp }.first()
        val added = stems.first { it !in setOf(plain, overridden, donorSame, removed, otherOpDonor, opChanged) }

        val old = linkedMapOf(
            "$plain.kpp" to KppFixtures.bytes(plain),
            "$overridden.kpp" to KppFixtures.bytes(overridden),
            "$removed.kpp" to KppFixtures.bytes(removed),
            "$opChanged.kpp" to KppFixtures.bytes(opChanged),
        )
        boot("v1", old)
        val ov = BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.4, "spacing" to 0.9), dirty = setOf("opacity", "spacing"))
        for (s in listOf(overridden, removed, opChanged)) {
            BrushOverrideStore.replace(BrushIdentity.builtinId(s), ov)
            EffectivePresetBuilder.materialize(BrushIdentity.builtinId(s), presetDir)
        }
        val removedEffBefore = eff(removed)

        // 新版 APK：plain/overridden 换成同 paintop 的另一个真实 kpp，opChanged 换成不同 paintop 的，removed 下线，added 新增
        val newPlain = KppFixtures.bytes(donorSame)
        val newOverridden = KppFixtures.bytes(removed)
        val newOpChanged = KppFixtures.bytes(stems.first { op(it) != op(opChanged) })
        val new = linkedMapOf(
            "$plain.kpp" to newPlain,
            "$overridden.kpp" to newOverridden,
            "$opChanged.kpp" to newOpChanged,
            "$added.kpp" to KppFixtures.bytes(added),
        )
        val d = BuiltinSnapshotMigrator.diff(
            old.mapValues { BrushDiagnostics.md5(it.value) }, new.mapValues { BrushDiagnostics.md5(it.value) },
        )
        assertEquals(setOf("$added.kpp"), d.added)
        assertEquals(setOf("$plain.kpp", "$overridden.kpp", "$opChanged.kpp"), d.changed)
        assertEquals(setOf("$removed.kpp"), d.removed)

        boot("v2", new)

        // 无覆盖 == 新 asset（== 全新安装）
        assertArrayEquals(newPlain, eff(plain))
        // 有覆盖 == 新 asset + 覆盖
        val oid = BrushIdentity.builtinId(overridden)
        assertEquals(ov, BrushOverrideStore.get(oid))
        assertArrayEquals(EffectivePresetBuilder.build(newOverridden, "kpp", ov), eff(overridden))
        assertNotEquals(BrushDiagnostics.md5(newOverridden), BrushDiagnostics.md5(eff(overridden)))
        BrushEditSession.reset(oid, presetDir)
        assertArrayEquals("升级后重置 == 新 asset", newOverridden, eff(overridden))
        // paintop 变了：只保留 LIVE 控件覆盖
        val cid = BrushIdentity.builtinId(opChanged)
        val kept = BrushOverrideStore.get(cid).controls.keys
        val live = ControlId.entries.filter { it.klass == ControlClass.LIVE }.map { it.key }.toSet()
        assertTrue("非 LIVE 覆盖应丢弃: $kept", kept.all { it in live })
        assertEquals("opacity" in live, "opacity" in kept)
        assertTrue("spacing" !in kept)
        assertArrayEquals(EffectivePresetBuilder.build(newOpChanged, "kpp", BrushOverrideStore.get(cid)), eff(opChanged))
        // 新增
        assertArrayEquals(KppFixtures.bytes(added), eff(added))
        assertNotNull(BrushIdentity.entry(BrushIdentity.builtinId(added)))
        // 删除：不删用户的笔刷，转为已下线导入快照，覆盖保留，字节不变
        assertNull(BrushIdentity.entry(BrushIdentity.builtinId(removed)))
        val r = BrushIdentity.entryForName(removed)!!
        assertEquals(BrushIdentity.Kind.IMPORTED, r.kind)
        assertEquals(BuiltinSnapshotMigrator.RETIRED_PACK, r.pack)
        assertEquals(BuiltinSnapshotMigrator.PROVENANCE_RETIRED, PresetSnapshotStore.source(r.id)!!.getString("provenance"))
        assertArrayEquals(KppFixtures.bytes(removed), PresetSnapshotStore.read(r.id))
        assertEquals(ov, BrushOverrideStore.get(r.id))
        assertArrayEquals(removedEffBefore, eff(removed))
        // 旧出厂目录已清理只剩当前版本 + 上一版本
        assertTrue((PresetSnapshotStore.factoryRoot().listFiles()?.count { it.isDirectory } ?: 0) <= 2)

        // 再启动同一版本：不再触发迁移，字节不变
        val snapshot = listOf(plain, overridden, opChanged, added, removed).associateWith { BrushDiagnostics.md5(eff(it)) }
        boot("v2", new)
        assertEquals(snapshot, listOf(plain, overridden, opChanged, added, removed).associateWith { BrushDiagnostics.md5(eff(it)) })
    }

    @Test
    fun upgradedBuiltinResetEqualsFreshInstall() {
        // W-06 的"重置 == 全新安装"在升级后再验一次：对 20 个真实内置逐个 编辑→升级→重置
        val stems = KppFixtures.all.map { it.first }.take(20)
        val old = stems.associate { "$it.kpp" to KppFixtures.bytes(it) }
        boot("a", old)
        val ov = BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.5), dirty = setOf("opacity"))
        stems.forEach { BrushOverrideStore.replace(BrushIdentity.builtinId(it), ov) }
        // 新版：每个文件换成"下一个"真实 kpp 的字节
        val new = stems.mapIndexed { i, s -> "$s.kpp" to KppFixtures.bytes(stems[(i + 1) % stems.size]) }.toMap()
        boot("b", new)
        for (s in stems) {
            BrushEditSession.reset(BrushIdentity.builtinId(s), presetDir)
            assertArrayEquals("重置 == 新 asset: $s", new.getValue("$s.kpp"), eff(s))
        }
        // 全新安装同样的新 asset → 字节一致
        val fresh = Files.createTempDirectory("fresh").toFile()
        try {
            val fp = File(fresh, "paintoppresets").apply { mkdirs() }
            BrushStoreBootstrap.resetForTest()
            assertTrue(BrushStoreBootstrap.run(fresh, fp, true, "b", { new }, migrate = { true }))
            for (s in stems) assertArrayEquals(File(fp, "$s.kpp").readBytes(), File(presetDir, "$s.kpp").readBytes())
        } finally {
            BrushStoreBootstrap.resetForTest()
            fresh.walkBottomUp().forEach { it.setWritable(true) }
            fresh.deleteRecursively()
        }
    }

    @Test
    fun upgradeChangedRetainsLiveAndAppOnlyWhenPaintOpChanges() {
        val stems = KppFixtures.all.map { it.first }
        val byOp = stems.groupBy { op(it) }
        val opA = byOp.values.first { it.size >= 2 }
        val opB = byOp.values.first { it !== opA }
        val brush = opA[0]
        val donorSameOp = opA[1]
        val donorDiffOp = opB[0]

        // 1. paintop 变了
        val old = mapOf("$brush.kpp" to KppFixtures.bytes(brush))
        boot("v1", old)

        val bid = BrushIdentity.builtinId(brush)
        val ov = BrushOverrideStore.Overrides(
            controls = mapOf("opacity" to 0.4, "spacing" to 0.9, "pressureEnabled" to false),
            dirty = setOf("opacity", "spacing", "pressureEnabled"),
            rawKeys = mapOf("custom_raw" to "val"),
        )
        BrushOverrideStore.replace(bid, ov)

        val newDiff = mapOf("$brush.kpp" to KppFixtures.bytes(donorDiffOp))
        boot("v2_diff", newDiff)

        val afterDiff = BrushOverrideStore.get(bid)
        assertEquals("LIVE 控件值保留", 0.4, afterDiff.controls["opacity"])
        assertEquals("APP_ONLY 控件值保留", false, afterDiff.controls["pressureEnabled"])
        assertNull("非 LIVE 非 APP_ONLY 被删", afterDiff.controls["spacing"])
        assertEquals("dirty 保留 LIVE 与 APP_ONLY", setOf("opacity", "pressureEnabled"), afterDiff.dirty)
        assertTrue("rawKeys 清空", afterDiff.rawKeys.isEmpty())

        // 2. paintop 不变时 overrides 原样保留
        boot("v1_same", old)
        BrushOverrideStore.replace(bid, ov)
        val newSame = mapOf("$brush.kpp" to KppFixtures.bytes(donorSameOp))
        boot("v2_same", newSame)
        val afterSame = BrushOverrideStore.get(bid)
        assertEquals("paintop 不变时 overrides 原样保留", ov, afterSame)
    }

    @Test
    fun `新出厂版本且已有 restored 快照时保留 restored 字节并跳过 upgradeChanged`() {
        val stem = KppFixtures.all.first().first
        val oldBytes = KppFixtures.bytes(stem)
        boot("v1", mapOf("$stem.kpp" to oldBytes))

        val bid = BrushIdentity.builtinId(stem)
        val restoredBytes = oldBytes + byteArrayOf(42, 43, 44)
        PresetSnapshotStore.replaceForRestore(bid, "kpp", restoredBytes)

        // 新出厂版本变更该笔
        val donor = KppFixtures.all.last().first
        val newBytes = KppFixtures.bytes(donor)
        boot("v2", mapOf("$stem.kpp" to newBytes))

        // 验证 snapshotFile 依然优先返回 restored 字节
        assertArrayEquals("应保留 restored 快照", restoredBytes, PresetSnapshotStore.read(bid))
    }

    @Test
    fun `新出厂版本移除且已有 restored 快照时 retire 优先使用 restored 字节`() {
        val stem = KppFixtures.all.first().first
        val oldBytes = KppFixtures.bytes(stem)
        boot("v1", mapOf("$stem.kpp" to oldBytes))

        val bid = BrushIdentity.builtinId(stem)
        val restoredBytes = oldBytes + byteArrayOf(99, 88, 77)
        PresetSnapshotStore.replaceForRestore(bid, "kpp", restoredBytes)

        // 新版本完全移除该笔
        boot("v2_empty", emptyMap())

        // 验证已下线生成 imported，快照为 restoredBytes
        val retired = BrushIdentity.all().firstOrNull { it.name == stem && it.kind == BrushIdentity.Kind.IMPORTED }
        assertNotNull("应被下线为 imported", retired)
        assertArrayEquals("下线快照应为 restored 字节", restoredBytes, PresetSnapshotStore.read(retired!!.id))
        assertNull("原 builtin restored 文件应被清理", PresetSnapshotStore.restoredFile(bid))
    }
}
