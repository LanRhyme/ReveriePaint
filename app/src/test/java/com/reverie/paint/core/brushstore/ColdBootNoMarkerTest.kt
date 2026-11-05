/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证冷启动无 pending 标记时，overrides 为准，materializeAll 保证有效文件正确（S5）。
 */
class ColdBootNoMarkerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var presetDir: File
    private lateinit var root: File

    @Before
    fun setUp() {
        filesDir = tempFolder.root
        presetDir = File(filesDir, "paintoppresets").apply { mkdirs() }
        root = File(filesDir, "brush_store").apply { mkdirs() }

        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(root)
    }

    @Test
    fun `无 pending 标记时 overrides 为准`() {
        val stem = KppFixtures.all.first().first
        val bytes = KppFixtures.bytes(stem)
        assertTrue(BrushStoreBootstrap.run(filesDir, presetDir, "1", { mapOf("$stem.kpp" to bytes) }, migrate = { true }))

        val id = "builtin:$stem"
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("size" to 99.0)))
        val res = EffectivePresetBuilder.materialize(id, presetDir)!!
        val expected = res.file.readBytes()

        // 模拟外部意外篡改但没有 pending 标记
        File(presetDir, "$stem.kpp").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(BrushStoreBootstrap.hasReconcilePending(filesDir))

        // 重启跑 run
        BrushStoreBootstrap.run(filesDir, presetDir, "1", { mapOf("$stem.kpp" to bytes) }, migrate = { true })

        // 验证有效文件被 materializeAll 恢复为 overrides 为准
        assertArrayEquals(expected, File(presetDir, "$stem.kpp").readBytes())
    }
}
