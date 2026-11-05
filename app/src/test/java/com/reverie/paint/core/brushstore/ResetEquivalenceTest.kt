/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.BrushStoreVm
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 重置语义（执行文档 W-06）：
 * - BuiltinResetEqualsFreshInstall：内置预设改过再重置 == 全新安装（字节与 UI 回读都相同），全部 268 个内置 kpp；
 * - ImportResetEquivalence：导入预设重置回到导入那一刻（不是出厂、不是空白）。
 */
class ResetEquivalenceTest {
    private lateinit var tmp: File

    private class NoOpEngine : BrushApply.EngineFacade {
        override fun loadBrushPreset(index: Int): Boolean = true
        override fun setBrushSize(size: Double) {}
        override fun setBrushOpacity(opacity: Double) {}
        override fun setBrushFlow(flow: Double) {}
        override fun setBrushAngle(angle: Double) {}
        override fun setBrushColor(color: String) {}
        override fun setBrushSecondaryColor(color: String) {}
        override fun setPresetIsEraser(isEraser: Boolean) {}
        override fun setBrushCompositeOp(op: String) {}
        override fun setToolMode(mode: Int) {}
        override fun brushPresetCount(): Int = 0
        override fun brushPresetName(index: Int): String = ""
    }

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("reset").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreVm.resetForTest()
        BrushApply.engine = NoOpEngine()
    }

    @After
    fun tearDown() {
        BrushApply.resetEngineForTest()
        BrushStoreVm.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    private val allAssets: Map<String, ByteArray> by lazy { KppFixtures.all.associate { "${it.first}.kpp" to KppFixtures.bytes(it.first) } }

    private fun install(root: File): File {
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { allAssets }, migrate = { true }))
        return presetDir
    }

    /** 模拟一次工作台编辑：回读 → 改若干控件 → 提交 */
    private fun edit(id: String, presetDir: File, file: File): Boolean {
        val xml = com.reverie.paint.core.KppHelper.readPresetXml(file.readBytes(), true)!!
        val edits = OverrideFixtures.editsFor(xml)
        if (edits.isEmpty()) return false
        for ((cid, v) in edits) {
            BrushStoreVm.stage(id, cid, v)
        }
        val res = BrushStoreVm.flush(id)
        return res != null
    }

    @Test
    fun `BuiltinResetEqualsFreshInstall 全部内置预设`() {
        // 全新安装的有效字节与 UI 回读作基准
        val fresh = install(File(tmp, "fresh"))
        val freshBytes = KppFixtures.all.associate { (stem, _) -> stem to File(fresh, "$stem.kpp").readBytes() }
        BrushStoreBootstrap.resetForTest()

        val dir = install(File(tmp, "used"))
        var edited = 0
        for ((stem, _) in KppFixtures.all) {
            val id = BrushIdentity.idForName(stem)!!
            val f = File(dir, "$stem.kpp")
            if (edit(id, dir, f)) {
                edited++
                assertFalse("$stem 编辑未生效", f.readBytes().contentEquals(freshBytes.getValue(stem)))
            }
            assertTrue(BrushEditSession.reset(id, dir) != null)
            assertTrue(BrushOverrideStore.get(id).isEmpty())
            assertArrayEquals("$stem 重置后字节 != 全新安装", freshBytes.getValue(stem), f.readBytes())
            assertEquals(
                "$stem 重置后 UI 回读 != 全新安装",
                PresetReadback.fromEffectiveBytes(freshBytes.getValue(stem)),
                PresetReadback.fromEffectiveBytes(f.readBytes()),
            )
        }
        assertTrue("参与的预设太少: $edited", edited >= 260)
        // 覆盖文件持久化后重开仍为空
        BrushOverrideStore.open(BrushStoreBootstrap.storeRoot(File(tmp, "used")))
        assertTrue(BrushOverrideStore.ids().isEmpty())
    }

    @Test
    fun `ImportResetEquivalence 导入预设重置回到导入时`() {
        val dir = install(File(tmp, "imp"))
        // 用不同类型的真实 kpp 作为"导入来源"：像素、涂抹、纹理、遮罩、喷枪
        val sources = KppFixtures.all.map { it.first }.filter { s ->
            listOf("Basic", "Smudge", "Texture", "WaterC", "Airbrush", "Pencil").any { s.contains(it, ignoreCase = true) }
        }.distinctBy { s -> listOf("Basic", "Smudge", "Texture", "WaterC", "Airbrush", "Pencil").first { s.contains(it, true) } }
        assertTrue(sources.size >= 5)
        for (stem in sources) {
            val id = BrushIdentity.newImportedId()
            val name = "导入_$stem"
            val bytes = KppFixtures.bytes(stem)
            PresetSnapshotStore.putImported(id, "kpp", bytes, JSONObject().put("pack", "test.zip").put("provenance", "imported"))
            BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, "test.zip"))
            EffectivePresetBuilder.materialize(id, dir)
            val f = File(dir, "$name.kpp")
            assertArrayEquals(stem, bytes, f.readBytes())
            assertTrue("$stem 编辑未生效", edit(id, dir, f))
            assertNotEquals(BrushDiagnostics.md5(bytes), BrushDiagnostics.md5(f.readBytes()))
            BrushEditSession.reset(id, dir)
            assertArrayEquals("$stem 重置后 != 导入时", bytes, f.readBytes())
            assertTrue(BrushOverrideStore.get(id).isEmpty())
        }
    }

    @Test
    fun `重置保留元数据、丢掉 APP_ONLY 调参`() {
        val legacy = BrushParams(streamline = 0.6, taper = 0.3, author = "某人", isAuthorLocked = true, description = "d")
        assertTrue(PresetReadback.appOnlyModified(legacy))
        val kept = PresetReadback.metadataOnly(legacy)!!
        assertFalse(PresetReadback.appOnlyModified(kept))
        assertEquals("某人", kept.author)
        assertEquals(true, kept.isAuthorLocked)
        assertEquals("d", kept.description)
        assertEquals(BrushParams().streamline, kept.streamline, 0.0)
        assertNull(PresetReadback.metadataOnly(BrushParams(streamline = 0.6)))
        assertFalse(PresetReadback.appOnlyModified(null))
    }

    @Test
    fun `重置后只保留rawKeys与METADATA四键`() {
        val dir = install(File(tmp, "reset_meta"))
        val stem = KppFixtures.all.first().first
        val id = BrushIdentity.newImportedId()
        val name = "test_meta_$stem"
        val bytes = KppFixtures.bytes(stem)
        val appBase = PresetReadback.baselineJson(BrushParams(pressureEnabled = false))
        PresetSnapshotStore.putImported(
            id, "kpp", bytes,
            JSONObject().put("pack", "").put("provenance", "imported").put("appBaseline", appBase),
        )
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED))
        EffectivePresetBuilder.materialize(id, dir)

        // 写入 kpp 控件 + "streamline" + "pressureEnabled"(设为 true，偏离基线 false) + METADATA 四键 + rawKeys
        val fullOverrides = BrushOverrideStore.Overrides(
            controls = mapOf(
                "spacing" to 0.25,
                "streamline" to 0.75,
                "pressureEnabled" to true,
                "author" to "自定义作者",
                "isAuthorLocked" to true,
                "description" to "测试说明",
                "version" to "3.0",
            ),
            dirty = setOf("spacing", "pressureEnabled"),
            rawKeys = mapOf("rawKey1" to "rawValue1"),
        )
        BrushOverrideStore.replace(id, fullOverrides)

        // 执行重置
        BrushEditSession.reset(id, dir)

        val after = BrushOverrideStore.get(id)
        // rawKeys 原样保留
        assertEquals(mapOf("rawKey1" to "rawValue1"), after.rawKeys)
        // controls 只剩 METADATA 4 键，kpp 控件与 streamline、pressureEnabled 都被清除
        assertEquals(
            setOf("author", "isAuthorLocked", "description", "version"),
            after.controls.keys,
        )
        assertEquals("自定义作者", after.controls["author"])
        assertEquals(true, after.controls["isAuthorLocked"])
        assertEquals("测试说明", after.controls["description"])
        assertEquals("3.0", after.controls["version"])
        assertFalse("spacing" in after.controls)
        assertFalse("streamline" in after.controls)
        assertFalse("pressureEnabled" in after.controls)

        // withAppControls 验证：非元数据 APP_ONLY 等于 baseline(id)
        val base = PresetReadback.baseline(id)
        assertEquals(false, base.pressureEnabled)
        assertNotEquals(BrushParams().pressureEnabled, base.pressureEnabled)
        val reconstructed = PresetReadback.withAppControls(base, after.controls)
        assertEquals(false, reconstructed.pressureEnabled)
        assertEquals(base.pressureEnabled, reconstructed.pressureEnabled)
        assertNotEquals(BrushParams().pressureEnabled, reconstructed.pressureEnabled)
        assertEquals(base.streamline, reconstructed.streamline, 0.0)
        assertEquals(base.taper, reconstructed.taper, 0.0)
        assertEquals(base.maskingSoftness, reconstructed.maskingSoftness, 0.0)
        assertEquals("自定义作者", reconstructed.author)
        assertEquals(true, reconstructed.isAuthorLocked)
        assertEquals("测试说明", reconstructed.description)
        assertEquals("3.0", reconstructed.version)

        // clear 之后 readback 的 pressureEnabled == false (== baseline)，且 != BrushParams().pressureEnabled
        BrushOverrideStore.clear(id)
        val cleared = BrushOverrideStore.get(id)
        val reconstructedCleared = PresetReadback.withAppControls(base, cleared.controls)
        assertEquals(false, reconstructedCleared.pressureEnabled)
        assertEquals(base.pressureEnabled, reconstructedCleared.pressureEnabled)
        assertNotEquals(BrushParams().pressureEnabled, reconstructedCleared.pressureEnabled)
    }

    @Test
    fun `hasTuning语义验证`() {
        // 只有 METADATA 键时 false
        val metaOnly = mapOf<String, Any>(
            "author" to "Krita",
            "isAuthorLocked" to true,
            "description" to "Some desc",
            "version" to 1,
        )
        assertFalse(PresetReadback.hasTuning(metaOnly))
        assertFalse(PresetReadback.hasTuning(emptyMap()))

        // 有 streamline 时 true
        assertTrue(PresetReadback.hasTuning(metaOnly + ("streamline" to 0.5)))

        // 有任一 kpp 控件时 true
        assertTrue(PresetReadback.hasTuning(metaOnly + ("spacing" to 0.1)))
        // 大小/不透明度/流量归按工具记忆，不算调参
        assertFalse(PresetReadback.hasTuning(mapOf("size" to 50.0)))
    }

    @Test
    fun `重置后自定义缩略图保持不变`() {
        val dir = install(File(tmp, "reset_thumb"))
        val stem = KppFixtures.all.first().first
        val id = BrushIdentity.idForName(stem)!!
        val file = File(dir, "$stem.kpp")

        // 写入一个独特的自定义缩略图
        val customThumb = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            1, 2, 3, 4
        )
        BrushThumbStore.put(id, customThumb)

        // 提交修改并重新生成有效文件
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("spacing" to 0.5)))
        EffectivePresetBuilder.materialize(id, dir)

        // 验证当前有效文件嵌入了自定义缩略图
        val snap = PresetSnapshotStore.read(id)!!
        val xmlBefore = com.reverie.paint.core.KppHelper.readPresetXml(file.readBytes(), false)!!
        val expectedBefore = com.reverie.paint.core.KppHelper.replacePresetXml(customThumb, xmlBefore)
        assertArrayEquals(expectedBefore, file.readBytes())

        // 执行重置
        BrushEditSession.reset(id, dir)

        // 验证重置后，controls 被清空，但有效 kpp 内部嵌入的缩略图仍然是自定义缩略图
        assertTrue(BrushOverrideStore.get(id).isEmpty())
        val xmlAfter = com.reverie.paint.core.KppHelper.readPresetXml(snap, false)!!
        val expectedAfter = com.reverie.paint.core.KppHelper.replacePresetXml(customThumb, xmlAfter)
        assertArrayEquals("重置后有效kpp内部缩略图不能恢复或丢失", expectedAfter, file.readBytes())
        assertArrayEquals("重置后BrushThumbStore必须保留自定义缩略图", customThumb, BrushThumbStore.get(id))
    }
}
