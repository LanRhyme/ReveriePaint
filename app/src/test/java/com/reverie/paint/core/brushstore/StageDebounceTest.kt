/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StageDebounceTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val id = BrushIdentity.builtinId(stem)

    private class RecordingEngine : BrushApply.EngineFacade {
        val liveSizes = mutableListOf<Double>()
        val liveOpacities = mutableListOf<Double>()
        val liveFlows = mutableListOf<Double>()
        val selectCalls = mutableListOf<Int>()
        var loadedPresets = mutableListOf<Int>()
        val presetNames = mutableListOf<String>()

        override fun loadBrushPreset(index: Int): Boolean {
            loadedPresets.add(index)
            return true
        }

        override fun setBrushSize(size: Double) { liveSizes.add(size) }
        override fun setBrushOpacity(opacity: Double) { liveOpacities.add(opacity) }
        override fun setBrushFlow(flow: Double) { liveFlows.add(flow) }
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
        tmp = Files.createTempDirectory("stage_debounce").toFile()
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
    fun `LIVE类型applyLive直推引擎且不触发rebuild、stage不处理大小不透明度流量`() = runBlocking {
        val file = File(presetDir, "$stem.kpp")
        val md5Before = BrushDiagnostics.md5(file.readBytes())
        val indexOf = { targetId: String -> fakeEngine.presetNames.indexOf(targetId.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }
        val host = FakeFlushHost(fakeEngine, indexOf, active = id)

        BrushApply.applyLive(ControlId.SIZE, 55.0)
        assertEquals(listOf(55.0), fakeEngine.liveSizes)

        BrushApply.applyLive(ControlId.OPACITY, 0.42)
        assertEquals(listOf(0.42), fakeEngine.liveOpacities)

        BrushApply.applyLive(ControlId.FLOW, 0.88)
        assertEquals(listOf(0.88), fakeEngine.liveFlows)

        // 这三个不再经 stage：stage 忽略它们（不推引擎、不写覆盖）
        BrushStoreVm.stage(id, ControlId.SIZE, 66.0, host)
        assertEquals(listOf(55.0), fakeEngine.liveSizes)
        assertTrue(BrushOverrideStore.get(id).controls.isEmpty())

        // 等待超过防抖窗口
        kotlinx.coroutines.delay(200L)

        // 验证没有触发 rebuild 与 select
        assertEquals(emptyList<Int>(), fakeEngine.loadedPresets)
        assertEquals(md5Before, BrushDiagnostics.md5(file.readBytes()))
    }

    @Test
    fun `REBUILD类型连续修改防抖120ms只flush一次`() = runBlocking {
        val file = File(presetDir, "$stem.kpp")
        val md5Before = BrushDiagnostics.md5(file.readBytes())
        val indexOf = { targetId: String -> fakeEngine.presetNames.indexOf(targetId.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }
        val host = FakeFlushHost(fakeEngine, indexOf, active = id)

        // 连续多次 stage REBUILD 控件
        BrushStoreVm.stage(id, ControlId.SPACING, 0.25, host)
        kotlinx.coroutines.delay(30L)
        BrushStoreVm.stage(id, ControlId.SPACING, 0.35, host)
        kotlinx.coroutines.delay(30L)
        BrushStoreVm.stage(id, ControlId.SPACING, 0.45, host)

        // 此时防抖 120ms 还没到，不应有 flush
        assertEquals(0, fakeEngine.loadedPresets.size)

        // 等待防抖超时并完成 flush
        val deadline = System.currentTimeMillis() + 1500L
        while (fakeEngine.loadedPresets.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20L)
        }

        // 验证只 flush / 重载了一次，且加载的是目标预设而非 0 号预设
        val expectedIdx = fakeEngine.presetNames.indexOf(stem)
        assertTrue("目标预设必须有有效索引", expectedIdx >= 0)
        assertEquals(listOf(expectedIdx), fakeEngine.loadedPresets)
        val md5After = BrushDiagnostics.md5(file.readBytes())
        assertTrue("有效文件应该被 materialize 更新", md5Before != md5After)
        assertEquals(0.45, (BrushOverrideStore.get(id).controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
    }

    @Test
    fun `防抖等待期间做的LIVE和APP_ONLY修改不会丢失且准确更新dirty`() = runBlocking {
        val indexOf = { targetId: String -> fakeEngine.presetNames.indexOf(targetId.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }
        val host = FakeFlushHost(fakeEngine, indexOf, active = id)

        // 先触发一次 REBUILD
        BrushStoreVm.stage(id, ControlId.SPACING, 0.45, host)
        kotlinx.coroutines.delay(20L)

        // 防抖期间做 LIVE 与 APP_ONLY 调参
        BrushStoreVm.stage(id, ControlId.ANGLE, 0.33, host)
        BrushStoreVm.stage(id, ControlId.STREAMLINE, 0.7, host)

        // 等待防抖 flush 完成
        val deadline = System.currentTimeMillis() + 1500L
        while (fakeEngine.loadedPresets.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20L)
        }

        val o = BrushOverrideStore.get(id)
        assertEquals(0.45, (o.controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
        assertEquals(0.33, (o.controls[ControlId.ANGLE.key] as Number).toDouble(), 1e-4)
        assertEquals(0.7, (o.controls[ControlId.STREAMLINE.key] as Number).toDouble(), 1e-4)
        assertTrue("spacing 应在 dirty 集合中", ControlId.SPACING.key in o.dirty)
        assertTrue("angle 应在 dirty 集合中", ControlId.ANGLE.key in o.dirty)
        assertTrue("streamline 应在 dirty 集合中", ControlId.STREAMLINE.key in o.dirty)
    }

    private fun hostFor(active: String? = id) =
        FakeFlushHost(fakeEngine, { t -> fakeEngine.presetNames.indexOf(t.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }, active = active)

    @Test
    fun `防抖任务在暂存已被取走后不重复 materialize 和重载`() {
        val host = hostFor()
        BrushStoreVm.stage(id, ControlId.SPACING, 0.3, host)
        assertEquals(1, BrushStoreVm.flush(id, host).let { host.reloads.size })      // 显式 flush（如 captureStudio）取走并重载一次
        assertEquals(null, BrushStoreVm.flush(id, host, onlyIfPending = true))        // 与它竞争的防抖任务：什么都不做
        assertEquals(1, host.reloads.size)
    }

    @Test
    fun `作用域取消后暂存的重建类修改由 flushAllPending 补写，不留已取消任务`() = runBlocking {
        val host = hostFor()
        host.scope.cancel()                                                            // 模拟 VM 清理
        BrushStoreVm.stage(id, ControlId.SPACING, 0.33, host)
        kotlinx.coroutines.delay(300L)
        assertTrue(BrushStoreVm.hasPending(id))
        assertTrue(BrushOverrideStore.get(id).controls.isEmpty())

        BrushStoreVm.flushAllPending()

        assertFalse(BrushStoreVm.hasPending(id))
        assertEquals(0.33, (BrushOverrideStore.get(id).controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
    }
}
