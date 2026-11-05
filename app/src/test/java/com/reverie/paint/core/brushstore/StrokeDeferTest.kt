/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StrokeDeferTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val id = BrushIdentity.builtinId(stem)

    private class RecordingEngine : BrushApply.EngineFacade {
        var loadedPresets = mutableListOf<Int>()
        val presetNames = mutableListOf<String>()
        override fun loadBrushPreset(index: Int): Boolean {
            loadedPresets.add(index)
            return true
        }
        override fun setBrushSize(v: Double) {}
        override fun setBrushOpacity(v: Double) {}
        override fun setBrushFlow(v: Double) {}
        override fun setBrushAngle(deg: Double) {}
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
        tmp = Files.createTempDirectory("stroke_defer").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreVm.resetForTest()
        val root = File(tmp, "files")
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        val allAssets = KppFixtures.all.associate { "${it.first}.kpp" to KppFixtures.bytes(it.first) }
        BrushStoreBootstrap.run(root, presetDir, true, "1", { allAssets }, migrate = { true })
        fakeEngine = RecordingEngine()
        fakeEngine.presetNames.add(stem)
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
    fun `笔划进行中推迟flush直到笔画结束才执行`() = runBlocking {
        val indexOf = { targetId: String -> fakeEngine.presetNames.indexOf(targetId.removePrefix(BrushIdentity.BUILTIN_PREFIX)) }
        val host = FakeFlushHost(fakeEngine, indexOf, active = id)

        // 模拟笔画进行中（例如 activeStrokeToolOverride = "eraser"）
        host.stroking = true

        // stage REBUILD 控件
        BrushStoreVm.stage(id, ControlId.SPACING, 0.45, host)

        // 等待超过 120ms 防抖
        delay(180L)

        // 验证因笔划进行中被推迟，未执行 flush
        assertTrue("笔划进行中应保持 pending", BrushStoreVm.hasPending(id))
        assertTrue(fakeEngine.loadedPresets.isEmpty())

        // 模拟笔划结束
        host.stroking = false

        // 轮询等待推迟的 flush 被消费
        val deadline = System.currentTimeMillis() + 1500L
        while (fakeEngine.loadedPresets.isEmpty() && System.currentTimeMillis() < deadline) {
            delay(20L)
        }

        val expectedIdx = fakeEngine.presetNames.indexOf(stem)
        assertTrue("目标预设必须有有效索引", expectedIdx >= 0)
        assertEquals(listOf(expectedIdx), fakeEngine.loadedPresets)
        assertFalse("笔画结束后 pending 应已被消费", BrushStoreVm.hasPending(id))
        assertEquals(0.45, (BrushOverrideStore.get(id).controls[ControlId.SPACING.key] as Number).toDouble(), 1e-4)
    }
}
