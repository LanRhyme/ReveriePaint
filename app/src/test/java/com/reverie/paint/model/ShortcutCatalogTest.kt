/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.painting.panels.labelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutCatalogTest {

    // ---- 目录完整性 ----

    @Test
    fun `shortcut ids are unique and every definition has a name resource`() {
        val ids = ALL_SHORTCUT_DEFINITIONS.map { it.id }
        assertEquals("动作 id 不允许重复", ids.size, ids.toSet().size)
        ALL_SHORTCUT_DEFINITIONS.forEach { def ->
            assertTrue("${def.id} 缺少名称资源", def.nameRes != 0)
        }
    }

    @Test
    fun `every section belongs to the category it is filed under`() {
        ALL_SHORTCUT_DEFINITIONS.forEach { def ->
            assertEquals(
                "动作 ${def.id} 的小节与分类不一致",
                def.category,
                def.section.category,
            )
        }
    }

    @Test
    fun `default key combos never collide with each other`() {
        val seen = mutableMapOf<String, String>()
        ALL_SHORTCUT_DEFINITIONS.forEach { def ->
            def.defaultKeys.forEach { key ->
                val norm = normalizeShortcut(key)
                if (norm.isEmpty()) return@forEach
                val previous = seen.put(norm, def.id)
                assertNull("出厂默认按键冲突: $norm <- ${def.id} 与 $previous", previous)
            }
        }
    }

    @Test
    fun `every paintable tool has a shortcut definition with the shared label`() {
        val paintableTools = listOf(
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE,
            Tool.FILL, Tool.ENCLOSE_FILL, Tool.GRADIENT,
            Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH,
            Tool.SELECT_RECT, Tool.SELECT_ELLIPSE, Tool.SELECT_POLYGON, Tool.LASSO, Tool.MAGICWAND, Tool.SELECT_SIMILAR,
            Tool.TRANSFORM, Tool.CROP,
            Tool.PICKER, Tool.TEXT, Tool.LIQUIFY, Tool.MEASURE, Tool.PAN,
        )
        paintableTools.forEach { tool ->
            val def = findShortcutDefinition("tool_${tool.id}")
            assertNotNull("工具 ${tool.id} 没有登记快捷键动作", def)
            assertEquals(
                "工具 ${tool.id} 的动作名与 UI 工具名不一致",
                tool.labelRes(),
                def!!.nameRes,
            )
        }
        // 工具名映射表必须覆盖全部 Tool
        Tool.entries.forEach { tool ->
            assertEquals("Tool.${tool.name} 的名称映射与 UI 不一致", tool.labelRes(), toolShortcutNameRes(tool))
        }
    }

    @Test
    fun `no shortcut definition is left without any default binding by accident except documented ones`() {
        // 重点动作必须有出厂按键；缺失会让用户以为快捷键系统坏了
        val mustHaveDefaults = listOf(
            "undo", "redo", "save_document", "tool_brush", "tool_eraser", "tool_smudge",
            "brush_size_inc", "brush_size_dec", "zoom_in", "zoom_out", "fit_canvas",
            "layer_new", "layer_duplicate", "layer_delete", "layer_merge_down",
            "deselect", "cut", "copy", "paste", "select_all", "flip_canvas", "swap_colors",
        )
        mustHaveDefaults.forEach { id ->
            val def = findShortcutDefinition(id)
            assertNotNull("缺少动作 $id", def)
            assertTrue("动作 $id 应该有出厂按键", def!!.defaultKeys.isNotEmpty())
        }
    }

    @Test
    fun `painting tools support hold to use temporarily while panel tools do not`() {
        // 绘制/填充/形状/选区: 可以长按临时借用, 松开回原工具
        listOf(
            "tool_brush", "tool_eraser", "tool_smudge",
            "tool_fill", "tool_enclose_fill", "tool_gradient",
            "tool_shapes", "tool_line", "tool_rect", "tool_ellipse", "tool_polygon",
            "tool_select_rect", "tool_lasso", "tool_magicwand",
        ).forEach { id ->
            assertTrue("$id 应支持长按临时使用", findShortcutDefinition(id)!!.holdTemporary)
        }
        // 会开面板/对话框/自身已是开关语义的工具不参与临时切换
        listOf(
            "tool_transform", "tool_crop", "tool_text", "tool_liquify", "tool_measure",
            "tool_pan", "tool_picker", "tool_drawing_guide",
            "undo", "layer_new", "undo",
        ).forEach { id ->
            assertFalse("$id 不应参与长按临时使用", findShortcutDefinition(id)!!.holdTemporary)
        }
    }

    @Test
    fun `temporary tool reverts only on a real hold of the same tool`() {
        // 快速点按: 永久切换, 不回切
        assertFalse(shouldRevertTemporaryTool(120L, "brush", "brush"))
        // 按住过阈值且仍是那把临时工具: 回切
        assertTrue(shouldRevertTemporaryTool(TEMPORARY_TOOL_HOLD_MS, "brush", "brush"))
        assertTrue(shouldRevertTemporaryTool(TEMPORARY_TOOL_HOLD_MS + 1500L, "eraser", "eraser"))
        // 期间工具被别的操作改掉: 不抢
        assertFalse(shouldRevertTemporaryTool(TEMPORARY_TOOL_HOLD_MS + 500L, "transform", "brush"))
        // 没记过临时工具
        assertFalse(shouldRevertTemporaryTool(TEMPORARY_TOOL_HOLD_MS + 500L, "brush", null))
    }

    // ---- 绑定取值语义 ----

    @Test
    fun `resolve uses defaults when absent and respects explicit unbind`() {
        val undo = findShortcutDefinition("undo")!!
        assertEquals(listOf("LeftCtrl + Z"), resolveShortcutKeys(emptyMap(), undo))
        // 显式解绑 = 空列表，不能回落到默认
        assertEquals(emptyList<String>(), resolveShortcutKeys(mapOf("undo" to emptyList()), undo))
        // 自定义优先
        assertEquals(listOf("Z"), resolveShortcutKeys(mapOf("undo" to listOf("Z")), undo))
    }

    @Test
    fun `customized detection compares against factory defaults`() {
        val undo = findShortcutDefinition("undo")!!
        assertFalse(isShortcutCustomized(emptyMap(), undo))
        assertTrue(isShortcutCustomized(mapOf("undo" to listOf("Z")), undo))
        assertTrue(isShortcutCustomized(mapOf("undo" to emptyList()), undo))
        // 只是换了修饰键写法 / 顺序，不算自定义
        assertFalse(isShortcutCustomized(mapOf("undo" to listOf("Ctrl + Z")), undo))
    }

    // ---- 冲突 ----

    @Test
    fun `conflicts are detected against resolved bindings`() {
        // 把「橡皮」改成 Z 会与撤销的出厂 Ctrl+Z 无关；但把「画笔」设成 E 会撞「橡皮」
        val conflicts = findShortcutConflicts(emptyMap(), "tool_brush", listOf("E"))
        assertEquals(listOf("tool_eraser"), conflicts.map { it.id })

        // 多按键里任何一条冲突都要报
        val multi = findShortcutConflicts(emptyMap(), "tool_brush", listOf("Q", "Ctrl + Z"))
        assertEquals(listOf("undo"), multi.map { it.id })
    }

    @Test
    fun `explicit unbind removes the action from conflict detection`() {
        val bindings = mapOf("tool_eraser" to emptyList<String>())
        assertTrue(findShortcutConflicts(bindings, "tool_brush", listOf("E")).isEmpty())
    }

    @Test
    fun `duplicate binding scan reports both sides`() {
        val bindings = mapOf(
            "brush_size_inc" to listOf("Q"),
            "brush_size_dec" to listOf("Q"),
        )
        val conflicting = findConflictingShortcutIds(bindings)
        assertTrue("brush_size_inc" in conflicting)
        assertTrue("brush_size_dec" in conflicting)
        assertFalse("undo" in conflicting)
    }

    // ---- 编解码 / 方案 ----

    @Test
    fun `encode and decode round trip keeps multi key bindings`() {
        val bindings = mapOf(
            "undo" to listOf("Z"),
            "redo" to listOf("LeftShift + Z", "LeftCtrl + Y"),
            "tool_brush" to emptyList(),
        )
        val text = encodeShortcutBindings(bindings)
        assertTrue(text.startsWith(SHORTCUT_SCHEME_HEADER))
        val decoded = decodeShortcutBindings(text)
        assertEquals(bindings, decoded)
    }

    @Test
    fun `decode drops unknown ids duplicates and empty placeholders`() {
        val text = """
            #ReveriePaint Shortcuts v1
            undo	Z	Z	无
            unknown_action	Q
            tool_brush	
        """.trimIndent()
        val decoded = decodeShortcutBindings(text)
        assertEquals(setOf("undo", "tool_brush"), decoded.keys)
        assertEquals(listOf("Z"), decoded["undo"])
        assertEquals(emptyList<String>(), decoded["tool_brush"])
        assertNull(decoded["unknown_action"])
    }

    @Test
    fun `decode keeps at most the supported number of keys per action`() {
        val text = "#ReveriePaint Shortcuts v1\nundo\tZ\tY\tX\tW"
        assertEquals(MAX_SHORTCUT_KEYS_PER_ACTION, decodeShortcutBindings(text)["undo"]?.size)
        assertEquals(listOf("Z", "Y"), decodeShortcutBindings(text)["undo"])
    }

    @Test
    fun `legacy json is tolerated by merge but not by tab decoder`() {
        // 制表符解析器不认 JSON; 旧版本迁移在 PaintViewModel.loadShortcuts 里做，这里只锁容错
        assertTrue(decodeShortcutBindings("{\"undo\":\"Ctrl + Z\"}").isEmpty())
        assertTrue(decodeShortcutBindings(null).isEmpty())
        assertTrue(decodeShortcutBindings("").isEmpty())
    }

    @Test
    fun `merging an imported scheme only overwrites the actions it lists`() {
        val current = mapOf("undo" to listOf("Z"), "redo" to listOf("Y"))
        val imported = mapOf("undo" to listOf("Ctrl + Z"))
        val merged = mergeShortcutScheme(current, imported)
        assertEquals(listOf("Ctrl + Z"), merged["undo"])
        assertEquals(listOf("Y"), merged["redo"])
        assertEquals(current, mergeShortcutScheme(current, emptyMap()))
    }

    // ---- 预设方案 ----

    @Test
    fun `preset overrides reference existing actions only`() {
        ShortcutPreset.entries.forEach { preset ->
            shortcutPresetOverrides(preset).keys.forEach { id ->
                assertNotNull("方案 ${preset.id} 引用了不存在的动作 $id", findShortcutDefinition(id))
            }
        }
    }

    @Test
    fun `every preset resolves to a conflict free binding table`() {
        ShortcutPreset.entries.forEach { preset ->
            val bindings = shortcutPresetOverrides(preset)
            val conflicting = findConflictingShortcutIds(bindings)
            assertTrue(
                "方案 ${preset.id} 内部存在按键冲突: $conflicting",
                conflicting.isEmpty(),
            )
        }
    }

    @Test
    fun `one hand preset drops every ctrl combination`() {
        val bindings = shortcutPresetOverrides(ShortcutPreset.ONE_HAND)
        ALL_SHORTCUT_DEFINITIONS.forEach { def ->
            resolveShortcutKeys(bindings, def).forEach { key ->
                assertFalse(
                    "单手方案里不该残留 Ctrl 组合: ${def.id} = $key",
                    normalizeShortcut(key).startsWith("CTRL"),
                )
            }
        }
        // 关键动作仍然可用
        assertEquals(listOf("Z"), resolveShortcutKeys(bindings, findShortcutDefinition("undo")!!))
    }

    @Test
    fun `default preset equals the factory table`() {
        assertTrue(shortcutPresetOverrides(ShortcutPreset.DEFAULT).isEmpty())
        // 已应用「默认」后绑定表为空 = 全部走出厂默认
        assertEquals(emptyMap<String, List<String>>(), shortcutPresetOverrides(ShortcutPreset.DEFAULT))
    }

    // ---- 搜索 ----

    @Test
    fun `search matches action name id and bound keys`() {
        val undo = findShortcutDefinition("undo")!!
        assertTrue(shortcutMatchesQuery(undo, "撤销", undo.defaultKeys, "撤"))
        assertTrue(shortcutMatchesQuery(undo, "Undo", undo.defaultKeys, "undo"))
        assertTrue(shortcutMatchesQuery(undo, "撤销", undo.defaultKeys, "ctrl+z"))
        assertFalse(shortcutMatchesQuery(undo, "撤销", undo.defaultKeys, "高斯"))
        // 空查询恒真
        assertTrue(shortcutMatchesQuery(undo, "撤销", undo.defaultKeys, "  "))
    }
}
