/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 缺口 5：.myb 没有 kpp XML，选中/回读不能退回 BrushParams() 的 20/1/1，
 * 而要取引擎对该预设解析出的原生默认值（上游 f55b4a0e0 的 brushPresetDefaults + resolvePresetSize 口径）。
 */
class MybReadbackTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val mybName = "c)_Pencil_2b_(mypaint)"
    private val mybId = BrushIdentity.builtinId(mybName)

    /** 与 ReverieCoreBrush.cpp info.defaults 同序：size, opacity, flow, spacing, airbrush, rate, smudgeRate, smudgeLen, angle, scatter, softness, ratio, sharpness, rotation, pSize, pOpacity, pFlow, follow, flipX, flipY, aa */
    private val nativeDefaults = doubleArrayOf(
        42.0, 0.7, 0.35, 0.2,
        0.0, 30.0, 0.0, 0.5,
        15.0, 0.25, 0.5, 1.0, 0.0, 0.0,
        1.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    private class Engine(val names: List<String>, val defaults: DoubleArray) : BrushApply.EngineFacade {
        override fun loadBrushPreset(index: Int) = true
        override fun setBrushSize(v: Double) {}
        override fun setBrushOpacity(v: Double) {}
        override fun setBrushFlow(v: Double) {}
        override fun setBrushAngle(deg: Double) {}
        override fun setBrushColor(color: String) {}
        override fun setBrushSecondaryColor(color: String) {}
        override fun setPresetIsEraser(isEraser: Boolean) {}
        override fun setBrushCompositeOp(op: String) {}
        override fun brushPresetCount() = names.size
        override fun brushPresetName(index: Int) = names.getOrElse(index) { "" }
        override fun brushPresetDefaults(index: Int): DoubleArray = defaults
    }

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("myb_readback").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreVm.resetForTest()
        val root = File(tmp, "files")
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        val assets = mapOf("$mybName.myb" to """{"version":3,"setting":{}}""".toByteArray())
        BrushStoreBootstrap.run(root, presetDir, true, "1", { assets }, migrate = { true })
        BrushApply.engine = Engine(listOf(mybName), nativeDefaults)
    }

    @After
    fun tearDown() {
        BrushStoreVm.resetForTest()
        BrushApply.resetEngineForTest()
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.deleteRecursively()
    }

    @Test
    fun `myb 回读取引擎原生默认值而不是 BrushParams 默认`() {
        val e = BrushStoreVm.resolveEntry(mybId)
        assertNotNull("内置 .myb 在 identity 里", e)
        assertEquals("myb", e!!.ext)
        val (p, f) = BrushStoreVm.readback(mybId)!!
        assertTrue(f.name.endsWith(".myb"))
        assertEquals(42.0, p.size, 1e-9)
        assertEquals(0.7, p.opacity, 1e-9)
        assertEquals(0.35, p.flow, 1e-9)
        assertEquals(0.2, p.spacing, 1e-9)
        assertEquals(15.0, p.angle, 1e-9)
        assertEquals(0.25, p.scatter, 1e-9)
    }

    @Test
    fun `原生大小不大于 1 时沿用 resolvePresetSize 兜底`() {
        BrushApply.engine = Engine(listOf(mybName), nativeDefaults.copyOf().also { it[0] = 1.0 })
        val (p, _) = BrushStoreVm.readback(mybId)!!
        assertEquals(1.0, p.size, 1e-9)
    }
}
