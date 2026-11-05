/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
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

class DuplicateCreateTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val builtinId = "builtin:$stem"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dup_create_test").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stems = listOf(stem, "c)_Pencil-3_Large_4B")
        assertTrue(
            BrushStoreBootstrap.run(
                root,
                presetDir,
                true,
                "1",
                { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } },
                migrate = { true },
            ),
        )
    }

    @After
    fun tearDown() {
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `duplicate preserves effective kpp bytes and sets author controls correctly`() {
        // 源是出厂笔：AUTHOR = "Krita (副本)", AUTHOR_LOCKED = false
        val dupBuiltinId = BrushImportPipeline.duplicate(builtinId, "DupBuiltin", presetDir, author = "Krita (副本)")
        assertNotNull(dupBuiltinId)

        val dupBuiltinEff = File(presetDir, "DupBuiltin.kpp").readBytes()
        val srcSnap = PresetSnapshotStore.read(builtinId)!!
        val srcOv = BrushOverrideStore.get(builtinId)
        val expectedBytes = EffectivePresetBuilder.build(srcSnap, "kpp", srcOv)
        assertArrayEquals(expectedBytes, dupBuiltinEff)

        val dupBuiltinOv = BrushOverrideStore.get(dupBuiltinId!!)
        assertEquals("Author should be Krita (副本)", "Krita (副本)", dupBuiltinOv.controls[ControlId.AUTHOR.key])
        assertEquals("Author locked should be false", false, dupBuiltinOv.controls[ControlId.AUTHOR_LOCKED.key])

        // 源是导入笔且 author = "X"
        val impId = BrushImportPipeline.create(builtinId, "Imp1", presetDir, author = "X", authorLocked = true)
        assertNotNull(impId)
        val impAuthor = PresetReadback.withAppControls(
            PresetReadback.baseline(impId!!),
            BrushOverrideStore.get(impId).controls,
        ).author
        assertEquals("Imp author should be X", "X", impAuthor)

        val dupImpId = BrushImportPipeline.duplicate(impId, "DupImp", presetDir, author = impAuthor)
        assertNotNull(dupImpId)

        val dupImpOv = BrushOverrideStore.get(dupImpId!!)
        assertEquals("Dup imp author should be X", "X", dupImpOv.controls[ControlId.AUTHOR.key])
        assertEquals("Dup imp author locked should be false", false, dupImpOv.controls[ControlId.AUTHOR_LOCKED.key])
    }

    @Test
    fun `create produces new preset with snapshot, effective file, and default author controls`() {
        val newId = BrushImportPipeline.create(builtinId, "NewPreset", presetDir)
        assertNotNull(newId)

        val file = File(presetDir, "NewPreset.kpp")
        assertTrue("有效文件必须存在", file.exists())

        val snap = PresetSnapshotStore.read(newId!!)
        assertNotNull("快照必须存在", snap)

        val ov = BrushOverrideStore.get(newId)
        assertEquals(
            "Controls should only contain default author and authorLocked",
            mapOf(
                ControlId.AUTHOR.key to "原创创作者",
                ControlId.AUTHOR_LOCKED.key to false,
            ),
            ov.controls,
        )
        assertFalse("新建笔刷不应被判定为调参 (hasTuning 为假)", PresetReadback.hasTuning(ov.controls))
    }

    @Test
    fun `create with tipAsset writes tip to snapshot and reset retains tip and author`() {
        val tipId = BrushImportPipeline.create(
            builtinId,
            "WithTip",
            presetDir,
            tipAsset = "foo.png",
            author = "外部创作者 (分享)",
            authorLocked = true,
        )
        assertNotNull(tipId)

        val effBytes = File(presetDir, "WithTip.kpp").readBytes()
        val xml = KppHelper.readPresetXml(effBytes, false)!!
        assertEquals("Tip asset should match foo.png", "foo.png", PresetPropertyMap.readTipAsset(xml))

        val ov = BrushOverrideStore.get(tipId!!)
        assertEquals("Author should match", "外部创作者 (分享)", ov.controls[ControlId.AUTHOR.key])
        assertEquals("Author locked should match", true, ov.controls[ControlId.AUTHOR_LOCKED.key])

        // reset 之后 tip 仍在，作者信息仍保留
        val resetResult = BrushEditSession.reset(tipId, presetDir)
        assertNotNull(resetResult)

        val resetBytes = File(presetDir, "WithTip.kpp").readBytes()
        val resetXml = KppHelper.readPresetXml(resetBytes, false)!!
        assertEquals("Tip asset should match foo.png after reset", "foo.png", PresetPropertyMap.readTipAsset(resetXml))

        val resetOv = BrushOverrideStore.get(tipId)
        assertEquals("Author should match after reset", "外部创作者 (分享)", resetOv.controls[ControlId.AUTHOR.key])
        assertEquals("Author locked should match after reset", true, resetOv.controls[ControlId.AUTHOR_LOCKED.key])
    }

    @Test
    fun `create with paintOpId writes paintOp to snapshot`() {
        val targetPaintOp = "colorsmudge"
        val paintOpId = BrushImportPipeline.create(
            builtinId,
            "WithPaintOp",
            presetDir,
            paintOpId = targetPaintOp,
        )
        assertNotNull(paintOpId)

        val effBytes = File(presetDir, "WithPaintOp.kpp").readBytes()
        val xml = KppHelper.readPresetXml(effBytes, false)!!
        assertEquals("PaintOp should match", targetPaintOp, KppXml.paintOpId(xml))
    }

    @Test
    fun `resolveBaseId falls back to fixed default builtin when base is myb or unknown`() {
        val mybId = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(mybId, "myb", """{"settings":{}}""".toByteArray(), org.json.JSONObject())
        BrushIdentity.register(BrushIdentity.Entry(mybId, "sketch", "myb", BrushIdentity.Kind.IMPORTED, ""))
        val expected = BrushIdentity.builtinId(BrushImportPipeline.DEFAULT_BUILTIN_BASE_NAME)
        assertEquals("myb cannot be a kpp base", expected, BrushImportPipeline.resolveBaseId(mybId))
        assertEquals("unknown id falls back", expected, BrushImportPipeline.resolveBaseId("imported:nope"))
        assertEquals("null falls back", expected, BrushImportPipeline.resolveBaseId(null))

        val newId = BrushImportPipeline.create(expected, "FromMybFallback", presetDir)
        assertNotNull(newId)
        assertTrue(File(presetDir, "FromMybFallback.kpp").exists())
    }
}
