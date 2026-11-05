/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 云同步后的磁盘对账测试（S5：reconcileDisk）。
 */
class SyncReconcileTest {
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
    fun `同步产生冲突副本新文件时通过 reconcileDisk 登记为导入笔`() {
        val stem = KppFixtures.all.first().first
        val bytes = KppFixtures.bytes(stem)
        assertTrue(BrushStoreBootstrap.run(filesDir, presetDir, "1", { mapOf("$stem.kpp" to bytes) }, migrate = { true }))

        // 模拟同步下载前标记
        BrushStoreBootstrap.beginReconcilePending(filesDir)

        // 模拟同步生成冲突副本文件
        val conflictFile = File(presetDir, "$stem (冲突副本).kpp")
        conflictFile.writeBytes(bytes)

        BrushStoreBootstrap.reconcileDisk(filesDir)
        assertFalse(BrushStoreBootstrap.hasReconcilePending(filesDir))

        val entry = BrushIdentity.entryForName("$stem (冲突副本)")
        assertNotNull("冲突副本应被登记", entry)
        assertTrue(PresetSnapshotStore.has(entry!!.id))
    }
}
