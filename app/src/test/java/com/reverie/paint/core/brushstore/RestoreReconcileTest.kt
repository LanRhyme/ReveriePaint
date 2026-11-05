/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 备份恢复后的磁盘对账测试（S5：reconcileDisk）。
 */
class RestoreReconcileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var presetDir: File
    private lateinit var brushDir: File
    private lateinit var patternDir: File
    private lateinit var root: File

    @Before
    fun setUp() {
        filesDir = tempFolder.root
        presetDir = File(filesDir, "paintoppresets").apply { mkdirs() }
        brushDir = File(filesDir, "brushes").apply { mkdirs() }
        patternDir = File(filesDir, "patterns").apply { mkdirs() }
        root = File(filesDir, "brush_store").apply { mkdirs() }

        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(root)
    }

    @Test
    fun `恢复覆盖的预设以恢复文件为准换掉快照并清空覆盖`() {
        val stem = KppFixtures.all.first().first
        val origBytes = KppFixtures.bytes(stem)
        val assets = mapOf("$stem.kpp" to origBytes)
        assertTrue(BrushStoreBootstrap.run(filesDir, presetDir, "1", { assets }, migrate = { true }))

        val id = "builtin:$stem"
        // 加一些 override
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("size" to 50.0)))
        EffectivePresetBuilder.materialize(id, presetDir)

        // 开始备份恢复前写 pending 标记
        BrushStoreBootstrap.beginReconcilePending(filesDir)
        assertTrue(BrushStoreBootstrap.hasReconcilePending(filesDir))

        // 模拟恢复：磁盘上的有效文件被替换为恢复出来的特定新字节
        val restoredBytes = origBytes + byteArrayOf(11, 22, 33)
        File(presetDir, "$stem.kpp").writeBytes(restoredBytes)

        // 触发对账
        BrushStoreBootstrap.reconcileDisk(filesDir)

        // 验证 pending 标记已删
        assertFalse(BrushStoreBootstrap.hasReconcilePending(filesDir))

        // 验证快照被换为恢复文件的字节
        assertArrayEquals(restoredBytes, PresetSnapshotStore.read(id))
        // 验证 overrides 被清空
        assertTrue(BrushOverrideStore.get(id).isEmpty())
    }

    @Test
    fun `未在快照中的全新预设自动 adoptFile 登记`() {
        val stem = KppFixtures.all.last().first
        val bytes = KppFixtures.bytes(stem)
        assertTrue(BrushStoreBootstrap.run(filesDir, presetDir, "1", { emptyMap() }, migrate = { true }))

        // 模拟恢复放下一个全新的 kpp
        val newFile = File(presetDir, "restored_external.kpp")
        newFile.writeBytes(bytes)

        BrushStoreBootstrap.beginReconcilePending(filesDir)
        BrushStoreBootstrap.reconcileDisk(filesDir)

        val entry = BrushIdentity.entryForName("restored_external")
        assertNotNull("全新预设应当被 adoptFile 成功登记", entry)
        assertTrue(PresetSnapshotStore.has(entry!!.id))
    }
}
