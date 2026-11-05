/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 Bootstrap 失败时进入只读兜底模式（S5）：
 * - 出厂文件仍然拷贝到有效目录；
 * - readOnly 状态置为 true；
 * - 各写入口拒绝写入。
 */
class BootstrapFailReadOnlyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var presetDir: File

    @Before
    fun setUp() {
        val f = tempFolder.root
        root = File(f, "invalid_store_file").apply {
            // 创建一个文件而非目录，使得 openStores 创建子目录失败抛出 IOException
            createNewFile()
        }
        presetDir = File(f, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
    }

    @Test
    fun `store 打开失败时拷贝有效文件并置 readOnly 为 true`() {
        val stems = KppFixtures.all.take(3).map { it.first }
        val assets = stems.associate { "$it.kpp" to KppFixtures.bytes(it) }

        val ok = BrushStoreBootstrap.run(
            filesDir = root, // 这里传文件会导致 openStores 失败
            presetDir = presetDir,
            installStamp = "1",
            readAssets = { assets },
            migrate = { true },
        )

        assertFalse("openStores 失败应返回 false", ok)
        assertTrue("应进入只读模式", BrushStoreBootstrap.readOnly)

        // 验证出厂预设已被拷入 presetDir
        for (s in stems) {
            val f = File(presetDir, "$s.kpp")
            assertTrue("出厂预设 $s 应当存在供只读使用", f.exists())
        }

        // 验证写入口检查 readOnly
        val targetFile = File(presetDir, "${stems.first()}.kpp")
        val md5Before = BrushDiagnostics.md5(targetFile.readBytes())
        val targetId = BrushIdentity.builtinId(stems.first())
        val overridesBefore = BrushOverrideStore.get(targetId)

        BrushStoreVm.stage(targetId, ControlId.SPACING, 0.45)
        val flushResult = BrushStoreVm.flush(targetId)

        assertNull("只读模式下 flush 应返回 null", flushResult)
        assertEquals("只读模式下 overrides 不应有任何改变", overridesBefore, BrushOverrideStore.get(targetId))
        assertEquals("只读模式下预设文件不应有任何改变", md5Before, BrushDiagnostics.md5(targetFile.readBytes()))

        val editSessionResetResult = BrushEditSession.reset("any_id", presetDir)
        assertNull(editSessionResetResult)

        val createResult = BrushImportPipeline.create("baseId", "newBrush", presetDir)
        assertTrue("只读模式下 create 应返回 null", createResult == null)
    }
}
