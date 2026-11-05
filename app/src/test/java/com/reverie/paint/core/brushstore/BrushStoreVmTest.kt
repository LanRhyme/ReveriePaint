/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrushStoreVmTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stemA = "b)_Basic-5_Size_Opacity"
    private val stemB = "c)_Pencil-3_Large_4B"
    private val idA = BrushIdentity.builtinId(stemA)
    private val idB = BrushIdentity.builtinId(stemB)

    private class RecordingEngine : BrushApply.EngineFacade {
        val loadedPresets = mutableListOf<Int>()
        val presetNames = mutableListOf<String>()

        override fun loadBrushPreset(index: Int): Boolean {
            loadedPresets.add(index)
            return true
        }

        override fun setBrushSize(size: Double) {}
        override fun setBrushOpacity(opacity: Double) {}
        override fun setBrushFlow(flow: Double) {}
        override fun setBrushAngle(angle: Double) {}
        override fun setBrushColor(color: String) {}
        override fun setBrushSecondaryColor(color: String) {}
        override fun setPresetIsEraser(isEraser: Boolean) {}
        override fun setBrushCompositeOp(op: String) {}
        override fun setToolMode(mode: Int) {}
        override fun brushPresetCount(): Int = presetNames.size
        override fun brushPresetName(index: Int): String = presetNames.getOrElse(index) { "" }
    }

    private lateinit var fakeEngine: RecordingEngine

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("brush_store_vm_test").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreVm.resetForTest()
        val root = File(tmp, "files")
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        val allAssets = KppFixtures.all.associate { "${it.first}.kpp" to KppFixtures.bytes(it.first) }
        BrushStoreBootstrap.run(root, presetDir, true, "1", { allAssets }, migrate = { true })
        fakeEngine = RecordingEngine()
        fakeEngine.presetNames.addAll(KppFixtures.all.map { it.first })
        BrushApply.engine = fakeEngine
    }

    @After
    fun tearDown() {
        BrushStoreVm.resetForTest()
        BrushApply.resetEngineForTest()
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    @Test
    fun `防抖期间换笔后flush不会切回被编辑的笔且覆盖已落盘`() = runBlocking {
        val idxA = fakeEngine.presetNames.indexOf(stemA)
        val idxB = fakeEngine.presetNames.indexOf(stemB)
        assertTrue("stemA 存在有效索引", idxA >= 0)
        assertTrue("stemB 存在有效索引", idxB >= 0)

        val indexOf = { id: String -> fakeEngine.presetNames.indexOf(id.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }
        val host = FakeFlushHost(fakeEngine, indexOf, active = idA)

        BrushStoreVm.stage(idA, ControlId.SPACING, 0.45, host)
        delay(30L)

        host.active = idB
        fakeEngine.loadBrushPreset(idxB)

        BrushStoreVm.stage(idB, ControlId.SPACING, 0.5, host)

        val deadline = System.currentTimeMillis() + 1500L
        while ((BrushStoreVm.hasPending(idA) || BrushStoreVm.hasPending(idB)) && System.currentTimeMillis() < deadline) {
            delay(20L)
        }
        delay(50L)

        assertEquals(listOf(idB), host.reloads)
        assertEquals(listOf(idxB, idxB), fakeEngine.loadedPresets)

        val oA = BrushOverrideStore.get(idA)
        assertEquals(0.45, (oA.controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
        val oB = BrushOverrideStore.get(idB)
        assertEquals(0.5, (oB.controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
    }

    @Test
    fun `stage修改REBUILD属性再改回原快照值后flush清除覆盖且文件回到快照md5`() = runBlocking {
        val fileA = File(presetDir, "$stemA.kpp")
        val md5Snap = BrushDiagnostics.md5(PresetSnapshotStore.read(idA)!!)
        val snapXml = com.reverie.paint.core.KppHelper.readPresetXml(PresetSnapshotStore.read(idA)!!, true)!!
        val origSpacing = PresetReadback.fromXml(snapXml).spacing

        // 1. 改成新值
        BrushStoreVm.stage(idA, ControlId.SPACING, origSpacing + 0.2)
        // 2. 改回原快照值
        BrushStoreVm.stage(idA, ControlId.SPACING, origSpacing)

        // 3. 执行 flush
        BrushStoreVm.flush(idA)

        // 4. 断言 controls 与 dirty 均被剪除，有效文件回到快照 md5
        val o = BrushOverrideStore.get(idA)
        org.junit.Assert.assertFalse("spacing 应不在 controls 中", o.controls.containsKey(ControlId.SPACING.key))
        org.junit.Assert.assertFalse("spacing 应不在 dirty 中", ControlId.SPACING.key in o.dirty)
        assertEquals("有效文件 md5 应回到快照 md5", md5Snap, BrushDiagnostics.md5(fileA.readBytes()))
    }

    @Test
    fun `基线为无压感预设仅stage其它参数不误产生压感覆盖与dirty`() = runBlocking {
        // 创建一个 appBaseline 中 pressureEnabled = false 的 ABR 预设
        val abrId = BrushIdentity.newImportedId()
        val abrName = "AbrBrushNoPressure"
        val snapBytes = KppFixtures.bytes(stemA)
        val appBase = PresetReadback.baselineJson(com.reverie.paint.core.BrushParams(pressureEnabled = false))
        PresetSnapshotStore.putImported(
            abrId, "kpp", snapBytes,
            org.json.JSONObject().put("provenance", "abr").put("appBaseline", appBase)
        )
        BrushIdentity.register(BrushIdentity.Entry(abrId, abrName, "kpp", BrushIdentity.Kind.IMPORTED))
        EffectivePresetBuilder.materialize(abrId, presetDir)

        // 仅修改 SPACING
        BrushStoreVm.stage(abrId, ControlId.SPACING, 0.35)
        BrushStoreVm.flush(abrId)

        val o = BrushOverrideStore.get(abrId)
        org.junit.Assert.assertFalse("controls 不应包含 pressureEnabled", o.controls.containsKey(ControlId.PRESSURE_ENABLED.key))
        org.junit.Assert.assertFalse("dirty 不应包含 pressureEnabled", ControlId.PRESSURE_ENABLED.key in o.dirty)
        assertTrue("controls 应包含 spacing", o.controls.containsKey(ControlId.SPACING.key))
    }

    @Test
    fun `captureStudio在防抖窗口内落盘并重载当前笔`() = runBlocking {
        val entry = BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stemA) ?: "")
        org.junit.Assert.assertNotNull("前置条件：stemA 必须有 managedEntry 才能继续", entry)

        val indexOf = { targetId: String -> fakeEngine.presetNames.indexOf(targetId.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }
        val host = FakeFlushHost(fakeEngine, indexOf, active = idA)

        BrushStoreVm.stage(idA, ControlId.SPACING, 0.45, host)
        val overrides = BrushStoreVm.captureStudio(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stemA) ?: "", host, emptyMap(), "brush")?.overrides

        org.junit.Assert.assertNotNull(overrides)
        assertEquals(0.45, (overrides!!.controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
        org.junit.Assert.assertFalse(BrushStoreVm.hasPending(idA))
        assertEquals(listOf(idA), host.reloads)

        delay(250L)
        assertEquals(1, host.reloads.size)
    }
}
