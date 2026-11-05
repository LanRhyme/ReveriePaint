package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 录制里的 isCustomized 必须与“回读非空”等价：未托管 / 文件不存在 / 无预设目录 → false；只 stat 不读盘。 */
class BrushStoreVmCustomizedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `customized only when the effective preset file exists`() {
        val dir = tmp.newFolder("presets")
        val id = BrushIdentity.builtinId("Some_Builtin")
        assertFalse("文件不存在（如 .myb 或未生成）", BrushStoreVm.hasEffectivePreset(id, dir))
        assertFalse("没有预设目录", BrushStoreVm.hasEffectivePreset(id, null))
        assertFalse("无法解析的 id", BrushStoreVm.hasEffectivePreset("not-an-id", dir))
        java.io.File(dir, "Some_Builtin.kpp").writeBytes(byteArrayOf(1))
        assertTrue(BrushStoreVm.hasEffectivePreset(id, dir))
    }
}
