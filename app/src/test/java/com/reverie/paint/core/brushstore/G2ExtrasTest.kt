/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * .myb 不归 v2 管：[BrushStoreBootstrap.managedEntry] 要求 ext==kpp，.myb 条目即便有快照也返回 null
 * （FORMAT_MATRIX #7）。
 */
class G2ExtrasTest {

    private lateinit var root: File
    private lateinit var presetDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("g2x").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stem = KppFixtures.all.first().first
        assertTrue(
            BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }),
        )
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.deleteRecursively()
    }

    // ---- .myb 也归 v2 管（v7 gap 5）----

    @Test
    fun `myb 条目有快照即 managedEntry 命中 ext 保持 myb`() {
        val stem = KppFixtures.all.first().first
        val mybId = BrushIdentity.newImportedId()
        val mybName = "G2X Myb 笔刷"
        PresetSnapshotStore.putImported(mybId, "myb", KppFixtures.bytes(stem), JSONObject().put("provenance", "import"))
        BrushIdentity.register(BrushIdentity.Entry(mybId, mybName, "myb", BrushIdentity.Kind.IMPORTED, ""))
        assertEquals(mybId, BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName(mybName) ?: "")?.id)
        assertEquals("myb", BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName(mybName) ?: "")?.ext)

        // 反例：身份在、快照缺失 → 不归管（原有的快照缺失检查仍成立）
        val ghostId = BrushIdentity.newImportedId()
        BrushIdentity.register(BrushIdentity.Entry(ghostId, "G2X Ghost", "myb", BrushIdentity.Kind.IMPORTED, ""))
        assertNull(BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName("G2X Ghost") ?: ""))
    }
}
