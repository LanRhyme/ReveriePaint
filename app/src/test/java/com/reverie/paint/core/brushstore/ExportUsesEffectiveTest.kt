/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushExportSource
import com.reverie.paint.core.KritaBundleManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 验证导出严格使用有效预设字节 (effectiveBytes) 的测试。
 * 确保调参 materialize 后的预设导出时产物忠实反映有效修改，
 * 未调参预设忠实使用出厂预设，导出过程不被外部状态污染。
 */
class ExportUsesEffectiveTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private lateinit var staging: File
    private val stems by lazy { KppFixtures.all.map { it.first }.take(10) }
    private val assets: (String) -> ByteArray? = { p ->
        File(KppFixtures.dir.parentFile, p).takeIf { it.isFile }?.readBytes()
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("export_effective").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        staging = File(root, "cache/export_staging")
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        assertTrue(BrushStoreBootstrap.run(
            root, presetDir, true, "1",
            { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } },
            migrate = { true }
        ))
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun editedPresetExportsEffectiveBytesDifferingFromFactory() {
        val s = stems[0]
        val id = BrushIdentity.builtinId(s)
        val factoryBytes = KppFixtures.bytes(s)

        // 1. 未编辑时，effectiveBytes 等于出厂字节
        val uneditedExport = BrushExportSource.effective(com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "", presetDir, assets)?.bytes
        assertNotNull(uneditedExport)
        assertArrayEquals(factoryBytes, uneditedExport)

        // 2. 调参并生成有效预设
        BrushOverrideStore.replace(
            id,
            BrushOverrideStore.Overrides(
                controls = mapOf("opacity" to 0.42, "spacing" to 0.75),
                dirty = setOf("opacity", "spacing")
            )
        )
        assertNotNull(EffectivePresetBuilder.materialize(id, presetDir))

        // 3. 调参后 effectiveBytes 必须严格等于 presetDir 中的有效预设字节，且与出厂字节不同
        val effectiveFile = File(presetDir, "$s.kpp")
        assertTrue(effectiveFile.isFile)
        val materializedBytes = effectiveFile.readBytes()

        val editedExp = BrushExportSource.effective(com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "", presetDir, assets)
        assertNotNull(editedExp)
        val editedExport = editedExp!!.bytes
        assertArrayEquals(materializedBytes, editedExport)
        assertTrue(BrushDiagnostics.md5(factoryBytes) != BrushDiagnostics.md5(editedExport))

        // 4. 打包导出验证 bundle 内容与 effectiveBytes 完全一致
        val staged = listOf(s to BrushExportSource.stage(s, editedExp, staging))
        val bundle = KritaBundleManager.exportBundle(
            bundleName = "EffectiveExport",
            groupName = "Group",
            presets = staged,
            tipAssets = emptyList(),
            customOutDir = File(root, "out")
        )
        ZipFile(bundle).use { z ->
            val entry = z.getEntry("paintoppresets/$s.kpp")
            assertNotNull(entry)
            assertArrayEquals(materializedBytes, z.getInputStream(entry).readBytes())
        }
    }

    @Test
    fun mixedPresetsExportStrictlyRespectiveEffectiveBytes() {
        // 对偶数索引预设进行编辑，奇数索引保持原样
        val edited = stems.filterIndexed { i, _ -> i % 2 == 0 }
        val pristine = stems.filterIndexed { i, _ -> i % 2 != 0 }

        for (s in edited) {
            val id = BrushIdentity.builtinId(s)
            BrushOverrideStore.replace(
                id,
                BrushOverrideStore.Overrides(
                    controls = mapOf("flow" to 0.55),
                    dirty = setOf("flow")
                )
            )
            EffectivePresetBuilder.materialize(id, presetDir)
        }

        for (s in stems) {
            val bytes = BrushExportSource.effective(com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "", presetDir, assets)!!.bytes
            val fileBytes = File(presetDir, "$s.kpp").readBytes()
            assertArrayEquals("导出必须与有效文件一致: $s", fileBytes, bytes)
            if (s in edited) {
                assertTrue("已编辑预设 md5 必须变化: $s", BrushDiagnostics.md5(bytes) != BrushDiagnostics.md5(KppFixtures.bytes(s)))
            } else {
                assertArrayEquals("未编辑预设必须等于出厂预设: $s", KppFixtures.bytes(s), bytes)
            }
        }
    }
}
