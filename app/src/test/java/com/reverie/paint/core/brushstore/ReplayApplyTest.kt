/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** gap1：回放的笔刷参数全部经 BrushApply 一处施加；门控语义（只在定制过时施加形状 / 保留原生间距）原样不变。 */
class ReplayApplyTest {
    private val calls = mutableListOf<String>()

    private val engine = object : BrushApply.EngineFacade {
        override fun loadBrushPreset(index: Int) = true
        override fun setPresetIsEraser(isEraser: Boolean) {}
        override fun setBrushCompositeOp(op: String) { calls += "op:$op" }
        override fun setBrushColor(color: String) { calls += "color:$color" }
        override fun setBrushSecondaryColor(color: String) { calls += "color2:$color" }
        override fun setBrushSize(v: Double) { calls += "size:$v" }
        override fun setBrushOpacity(v: Double) { calls += "opacity:$v" }
        override fun setBrushFlow(v: Double) { calls += "flow:$v" }
        override fun setBrushAngle(deg: Double) { calls += "angle:$deg" }
        override fun setToolMode(mode: Int) { calls += "tool:$mode" }
    }

    private val replay = object : BrushApply.ReplayEngine {
        override fun setBrushSoftness(v: Double) { calls += "softness:$v" }
        override fun setBrushSpacing(v: Double) { calls += "spacing:$v" }
        override fun setBrushScatter(v: Double) { calls += "scatter:$v" }
        override fun setBrushRotation(v: Double) { calls += "rotation:$v" }
        override fun setBrushRatio(v: Double) { calls += "ratio:$v" }
        override fun setBrushSharpness(v: Double) { calls += "sharpness:$v" }
        override fun setBrushSmudgeRate(v: Double) { calls += "smudgeRate:$v" }
        override fun setBrushSmudgeLength(v: Double) { calls += "smudgeLen:$v" }
        override fun setBrushAirbrush(enabled: Boolean, rate: Double) { calls += "airbrush:$enabled:$rate" }
        override fun setBrushFade(v: Double) { calls += "fade:$v" }
    }

    @Before
    fun setUp() {
        BrushApply.engine = engine
        BrushApply.replayEngine = replay
    }

    @After
    fun tearDown() = BrushApply.resetEngineForTest()

    private fun ext(preset: Int, overrides: Int) = BrushApply.applyReplayExt(
        preset, overrides, 0.1, 0.2, 3.0, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, "#abc", true, 0.3,
    )

    private val tail = listOf("smudgeRate:0.8", "smudgeLen:0.9", "color2:#abc", "airbrush:true:0.3")

    @Test
    fun `CONTEXT 按录制顺序施加工具模式 尺寸 不透明度 流量 混合 颜色`() {
        BrushApply.applyReplayContext(1, 12.0, 0.5, 0.25, "erase", "#112233")
        assertEquals(listOf("tool:1", "size:12.0", "opacity:0.5", "flow:0.25", "op:erase", "color:#112233"), calls)
    }

    @Test
    fun `预设被定制过时施加形状 含间距`() {
        ext(preset = 5, overrides = 1)
        assertEquals(listOf("softness:0.1", "spacing:0.2", "angle:3.0", "scatter:0.4", "rotation:0.5", "ratio:0.6", "sharpness:0.7") + tail, calls)
    }

    @Test
    fun `定制过但没动间距的预设保留原生间距`() {
        ext(preset = 5, overrides = 3)
        assertEquals(listOf("softness:0.1", "angle:3.0", "scatter:0.4", "rotation:0.5", "ratio:0.6", "sharpness:0.7") + tail, calls)
    }

    @Test
    fun `没被定制的预设只施加涂抹 副色 喷枪`() {
        ext(preset = 5, overrides = 0)
        assertEquals(tail, calls)
    }

    @Test
    fun `无预设会话总是施加形状`() {
        ext(preset = -1, overrides = 0)
        assertEquals(listOf("softness:0.1", "spacing:0.2", "angle:3.0", "scatter:0.4", "rotation:0.5", "ratio:0.6", "sharpness:0.7") + tail, calls)
    }

    @Test
    fun `渐隐走同一入口`() {
        BrushApply.applyReplayFade(0.75)
        assertEquals(listOf("fade:0.75"), calls)
    }
}
