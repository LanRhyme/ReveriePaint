/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.BrushStoreVm
import com.reverie.paint.core.PaintViewModel.ToolBrushState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 大小/不透明度/流量只归按工具记忆：覆盖里不留、已修改不算、工作台回滚还原、旧覆盖启动时搬走。 */
class ToolMemoryOwnerTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val id = BrushIdentity.builtinId(stem)

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("tool_memory_owner").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushStoreVm.resetForTest()
        val root = File(tmp, "files")
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        val assets = KppFixtures.all.associate { "${it.first}.kpp" to KppFixtures.bytes(it.first) }
        BrushStoreBootstrap.run(root, presetDir, true, "1", { assets }, migrate = { true })
    }

    @After
    fun tearDown() {
        BrushStoreVm.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.deleteRecursively()
    }

    private fun factory() = PresetReadback.snapshotParams(id)!!.second

    private fun effective(): BrushParams {
        val f = EffectivePresetBuilder.targetFile(presetDir, BrushIdentity.entry(id)!!)
        return PresetReadback.fromEffectiveBytes(f.readBytes(), PresetReadback.baseline(id))
    }

    @Test
    fun `studio revert restores this tool's size opacity flow`() {
        val before = mapOf("brush" to ToolBrushState(presetId = id, paramMemory = mapOf(id to listOf(10.0, 0.5, 0.6))))
        val snap = BrushStoreVm.captureStudio(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stem) ?: "", null, before, "brush")!!
        val edited = mapOf("brush" to ToolBrushState(presetId = id, paramMemory = mapOf(id to listOf(33.0, 0.2, 0.3))))
        assertTrue(BrushStoreVm.studioChanged(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stem) ?: "", snap, edited))
        val reverted = BrushStoreVm.revertStudio(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stem) ?: "", snap, edited)!!
        assertEquals(listOf(10.0, 0.5, 0.6), reverted["brush"]!!.paramMemory[id])
        assertFalse(BrushStoreVm.studioChanged(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stem) ?: "", snap, reverted))
    }

    @Test
    fun `studio revert drops a memory entry that did not exist on entry`() {
        val before = mapOf("brush" to ToolBrushState(presetId = id))
        val snap = BrushStoreVm.captureStudio(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stem) ?: "", null, before, "brush")!!
        val edited = mapOf("brush" to ToolBrushState(presetId = id, paramMemory = mapOf(id to listOf(33.0, 0.2, 0.3))))
        val reverted = BrushStoreVm.revertStudio(com.reverie.paint.core.brushstore.BrushIdentity.idForName(stem) ?: "", snap, edited)!!
        assertNull(reverted["brush"]!!.paramMemory[id])
    }

    @Test
    fun `size opacity flow edits never reach overrides so other tools get factory and brush is not modified`() {
        BrushStoreVm.stage(id, ControlId.SIZE, 33.0)
        BrushStoreVm.stage(id, ControlId.OPACITY, 0.2)
        BrushStoreVm.stage(id, ControlId.FLOW, 0.3)
        BrushStoreVm.flush(id)
        assertTrue(BrushOverrideStore.get(id).controls.isEmpty())
        assertFalse(PresetReadback.hasTuning(BrushOverrideStore.get(id).controls))
        assertFalse(BrushStoreVm.hasPending(id))
        val f = factory()
        val eff = effective()
        assertEquals(f.size, eff.size, 1e-9)
        assertEquals(f.opacity, eff.opacity, 1e-9)
        assertEquals(f.flow, eff.flow, 1e-9)
        assertFalse(PresetReadback.hasTuning(mapOf("size" to 1.0, "opacity" to 1.0, "flow" to 1.0)))
        assertTrue(PresetReadback.hasTuning(mapOf("size" to 1.0, "spacing" to 0.3)))
    }

    @Test
    fun `migration moves legacy overrides into tool memory and strips them once`() {
        val f = factory()
        BrushOverrideStore.replace(
            id,
            BrushOverrideStore.Overrides(
                controls = mapOf("size" to 33.0, "opacity" to 0.4, "spacing" to 0.3),
                dirty = setOf("size", "opacity", "spacing"),
            ),
        )
        val own = listOf(5.0, 0.9, 0.9)
        val states = mapOf(
            "brush" to ToolBrushState(presetId = id, paramMemory = mapOf(id to own)),
            "eraser" to ToolBrushState(presetId = "x"),
            "smudge" to ToolBrushState(presetId = "y", paramMemory = mapOf("z" to listOf(1.0, 1.0, 1.0))),
        )
        var persisted = 0
        val next = ToolMemoryMigration.run(states) { persisted++ }
        assertEquals("已有记忆条目的工具保持原值", own, next["brush"]!!.paramMemory[id])
        assertEquals(listOf(33.0, 0.4, f.flow), next["eraser"]!!.paramMemory[id])
        assertEquals(listOf(33.0, 0.4, f.flow), next["smudge"]!!.paramMemory[id])
        assertEquals(listOf(1.0, 1.0, 1.0), next["smudge"]!!.paramMemory["z"])
        assertEquals(3, next.size)
        val left = BrushOverrideStore.get(id)
        assertEquals(setOf("spacing"), left.controls.keys)
        assertEquals(setOf("spacing"), left.dirty)
        assertEquals(1, persisted)

        // 幂等：再跑一次什么都不变、不再落盘
        val again = ToolMemoryMigration.run(next) { persisted++ }
        assertEquals(next, again)
        assertEquals(1, persisted)
    }
}
