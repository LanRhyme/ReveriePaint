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
        override fun setToolMode(mode: Int) { calls += "setToolMode:$mode" }
        override fun beginPresetOverrideSnapshot(): Boolean { calls += "beginPresetOverrideSnapshot"; return true }
        override fun endPresetOverrideSnapshot(): Boolean { calls += "endPresetOverrideSnapshot"; return true }
    }

    /** ReverieCoreBridge 中 kpp 语义（REBUILD 类）的 setter，applier 永远不应暴露/调用 */
    private val rebuildSetters = setOf(
        "setBrushTexture", "setBrushOptionDynamics", "setBrushPressureDynamics", "setBrushJitter",
        "setBrushSmudgeRate", "setBrushSmudgeLength", "setBrushAirbrush", "setBrushMirror",
        "setBrushAntiAliasing", "setBrushFollowDirection", "setBrushTipAsset", "setBrushSpacing",
        "setBrushScatter", "setBrushFade", "setBrushSoftness", "setBrushRatio", "setBrushSharpness",
        "setBrushRotation",
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
    fun `附加旋转为 0 时不动 kpp 角度`() {
        val p = com.reverie.paint.core.BrushParams(angle = 10.0, rotation = 0.0)
        assertEquals(null, BrushApply.liveAngle(p))
        assertEquals(350.0, BrushApply.liveAngle(p.copy(rotation = -20.0))!!, 1e-9)
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
