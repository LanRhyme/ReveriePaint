/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 录制 FakeEngine 的调用序列：select（只补非 preset 状态）、临时橡皮、对称三个场景的原生调用顺序固定。
 */
private data class Call(val name: String, val args: List<Any?>)

class BrushApplySequenceTest {

    private class RecordingEngine : BrushApply.EngineFacade {
        val calls = mutableListOf<Call>()
        override fun loadBrushPreset(index: Int): Boolean { calls += Call("loadBrushPreset", listOf(index)); return true }
        override fun setPresetIsEraser(isEraser: Boolean) { calls += Call("setPresetIsEraser", listOf(isEraser)) }
        override fun setBrushCompositeOp(op: String) { calls += Call("setBrushCompositeOp", listOf(op)) }
        override fun setBrushColor(color: String) { calls += Call("setBrushColor", listOf(color)) }
        override fun setBrushSecondaryColor(color: String) { calls += Call("setBrushSecondaryColor", listOf(color)) }
        override fun setBrushSize(v: Double) { calls += Call("setBrushSize", listOf(v)) }
        override fun setBrushOpacity(v: Double) { calls += Call("setBrushOpacity", listOf(v)) }
        override fun setBrushFlow(v: Double) { calls += Call("setBrushFlow", listOf(v)) }
        override fun setBrushAngle(deg: Double) { calls += Call("setBrushAngle", listOf(deg)) }
        override fun setToolMode(mode: Int) { calls += Call("setToolMode", listOf(mode)) }
        override fun beginPresetOverrideSnapshot(): Boolean { calls += Call("beginPresetOverrideSnapshot", emptyList()); return true }
        override fun endPresetOverrideSnapshot(): Boolean { calls += Call("endPresetOverrideSnapshot", emptyList()); return true }
    }

    @Test
    fun `select 场景调用序列：只补非 preset 状态`() {
        val engine = RecordingEngine()
        val oldEngine = BrushApply.engine
        try {
            BrushApply.engine = engine
            val nonPreset = BrushApply.NonPresetState(
                isEraser = false,
                color = "#000000",
                secondaryColor = "#ffffff",
                compositeOverride = "normal",
                size = 20.0,
                opacity = 1.0,
                flow = 1.0,
                angleDeg = null,
            )
            engine.loadBrushPreset(5)
            BrushApply.applyAfterLoad(engine, nonPreset)

            // S4 原生分发只走 BrushApply：只补非 preset 状态，不调用任何 REBUILD setter
            val expected = listOf(
                "loadBrushPreset",
                "setPresetIsEraser",
                "setBrushCompositeOp",
                "setBrushColor",
                "setBrushSecondaryColor",
                "setBrushSize",
                "setBrushOpacity",
                "setBrushFlow",
            )
            assertEquals(expected, engine.calls.map { it.name })
        } finally {
            BrushApply.engine = oldEngine
        }
    }

    @Test
    fun `临时橡皮场景调用序列`() {
        val engine = RecordingEngine()
        val oldEngine = BrushApply.engine
        try {
            BrushApply.engine = engine
            BrushApply.beginEraserOverride(
                eraserPresetIdx = 1,
                currentPresetIdx = 0,
                size = 30.0,
                opacity = 1.0,
                flow = 1.0,
            )
            BrushApply.endOverride()

            val expected = listOf(
                "beginPresetOverrideSnapshot",
                "loadBrushPreset",
                "setPresetIsEraser",
                "setBrushCompositeOp",
                "setBrushSize",
                "setBrushOpacity",
                "setBrushFlow",
                "setToolMode",
                "endPresetOverrideSnapshot",
            )
            assertEquals(expected, engine.calls.map { it.name })
        } finally {
            BrushApply.engine = oldEngine
        }
    }

    @Test
    fun `对称场景调用序列`() {
        val engine = RecordingEngine()
        val oldEngine = BrushApply.engine
        try {
            BrushApply.engine = engine
            BrushApply.applySymmetry(mode = 1, isEraser = true)

            val expected = listOf(
                "setToolMode",
                "setPresetIsEraser",
                "setBrushCompositeOp",
            )
            assertEquals(expected, engine.calls.map { it.name })
        } finally {
            BrushApply.engine = oldEngine
        }
    }
}
