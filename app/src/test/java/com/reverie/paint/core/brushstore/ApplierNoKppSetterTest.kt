/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ApplierNoKppSetterTest（执行文档 W-04 / S4）：BrushApply 只调用"非 preset 状态"与 LIVE 入口 */
class ApplierNoKppSetterTest {

    private class Recorder(val loadOk: Boolean = true) : BrushApply.EngineFacade {
        val calls = ArrayList<String>()
        override fun loadBrushPreset(index: Int): Boolean { calls += "loadBrushPreset"; return loadOk }
        override fun setPresetIsEraser(isEraser: Boolean) { calls += "setPresetIsEraser" }
        override fun setBrushCompositeOp(op: String) { calls += "setBrushCompositeOp:$op" }
        override fun setBrushColor(color: String) { calls += "setBrushColor" }
        override fun setBrushSecondaryColor(color: String) { calls += "setBrushSecondaryColor" }
        override fun setBrushSize(v: Double) { calls += "setBrushSize" }
        override fun setBrushOpacity(v: Double) { calls += "setBrushOpacity" }
        override fun setBrushFlow(v: Double) { calls += "setBrushFlow" }
        override fun setBrushAngle(deg: Double) { calls += "setBrushAngle" }
        override fun setBrushRotation(deg: Double) { calls += "setBrushRotation" }
        override fun setToolMode(mode: Int) { calls += "setToolMode:$mode" }
        override fun beginPresetOverrideSnapshot(): Boolean { calls += "beginPresetOverrideSnapshot"; return true }
        override fun endPresetOverrideSnapshot(): Boolean { calls += "endPresetOverrideSnapshot"; return true }
    }

    /**
     * ReverieCoreBridge 中 kpp 语义（REBUILD 类）的 setter，applier 永远不应暴露/调用。
     * setBrushRotation 不在其列：它只按 (基础角度 + 附加偏角) 重算静态笔尖角度，不重建预设，
     * 与 setBrushAngle 同属 LIVE（曾被误分类，导致「附加旋转偏角」用 setBrushAngle 下发时
     * 把基础角度覆盖成偏角值）。
     */
    private val rebuildSetters = setOf(
        "setBrushTexture", "setBrushOptionDynamics", "setBrushPressureDynamics", "setBrushJitter",
        "setBrushSmudgeRate", "setBrushSmudgeLength", "setBrushAirbrush", "setBrushMirror",
        "setBrushAntiAliasing", "setBrushFollowDirection", "setBrushTipAsset", "setBrushSpacing",
        "setBrushScatter", "setBrushFade", "setBrushSoftness", "setBrushRatio", "setBrushSharpness",
    )

    @Test
    fun `门面接口不含任何 REBUILD setter`() {
        val names = BrushApply.EngineFacade::class.java.methods.map { it.name }.toSet()
        assertTrue(names.intersect(rebuildSetters).isEmpty())
    }

    @Test
    fun `applyAfterLoad 先 load 再只补非 preset 状态`() {
        val r = Recorder()
        r.loadBrushPreset(3)
        BrushApply.applyAfterLoad(
            r,
            BrushApply.NonPresetState(isEraser = false, color = "#112233", secondaryColor = "#ffffff"),
        )
        assertEquals(listOf("loadBrushPreset", "setPresetIsEraser", "setBrushColor", "setBrushSecondaryColor"), r.calls)
    }

    @Test
    fun `橡皮擦覆盖 compositeOp，工具记忆与附加旋转走 LIVE 入口`() {
        val r = Recorder()
        r.loadBrushPreset(0)
        BrushApply.applyAfterLoad(
            r,
            BrushApply.NonPresetState(
                isEraser = true, color = "#000000", secondaryColor = "#ffffff", compositeOverride = "erase",
                size = 12.0, opacity = 0.5, flow = Double.NaN, angleDeg = 30.0,
            ),
        )
        assertEquals(
            listOf(
                "loadBrushPreset", "setPresetIsEraser", "setBrushCompositeOp:erase", "setBrushColor",
                "setBrushSecondaryColor", "setBrushSize", "setBrushOpacity", "setBrushAngle",
            ),
            r.calls,
        )
    }

    @Test
    fun `基础角度与附加旋转偏角分开下发 由引擎内部合成`() {
        val r = Recorder()
        BrushApply.applyAfterLoad(
            r,
            BrushApply.NonPresetState(
                isEraser = false, color = "#000000", secondaryColor = "#ffffff",
                angleDeg = 10.0, rotationDeg = -20.0,
            ),
        )
        // 必须是两次独立 setter: 预先把 (基础角度+偏角) 合并后只调 setBrushAngle, 会让引擎的
        // m_brushRotation 与 m_brushAngle 失去一致性 —— 之后拖动任一滑块角度都会跳错
        assertEquals(
            listOf(
                "setPresetIsEraser", "setBrushColor", "setBrushSecondaryColor",
                "setBrushAngle", "setBrushRotation",
            ),
            r.calls,
        )
    }

    @Test
    fun `override 包住 begin 和 endSnapshot`() {
        val r = Recorder()
        val oldEngine = BrushApply.engine
        try {
            BrushApply.engine = r
            val result = BrushApply.override {
                r.calls += "inside"
                42
            }
            assertEquals(42, result)
            assertEquals(listOf("beginPresetOverrideSnapshot", "inside", "endPresetOverrideSnapshot"), r.calls)
        } finally {
            BrushApply.engine = oldEngine
        }
    }
}
