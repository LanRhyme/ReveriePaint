/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.reverie.paint.R

// ============================================================================
// 快捷键目录 (Shortcut Catalog)
//
// 对标 Krita「配置 Krita → 快捷键」：整个应用的可绑定动作集中登记在这张表里，
// 分组/分节展示，每个动作可挂**多个**按键组合（Krita 的主按键 + 备选按键）。
// 这里只放纯数据与纯函数（不碰 Android 框架），分发执行见
// core/PaintViewModelShortcutActions.kt，UI 见 ui/.../ShortcutSettingsPage.kt。
// ============================================================================

/** 顶层分类，对应设置页上的分类胶囊。 */
enum class ShortcutCategory(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    PAINTING("painting", R.string.shortcut_cat_painting, R.drawable.ic_brush),
    TOOLS("tools", R.string.shortcut_cat_tools, R.drawable.ic_grid),
    VIEW("view", R.string.shortcut_cat_view, R.drawable.ic_focus_view),
    EDIT("edit", R.string.shortcut_cat_edit, R.drawable.ic_undo),
    LAYERS("layers", R.string.shortcut_cat_layers, R.drawable.ic_layers),
    SELECTION("selection", R.string.shortcut_cat_selection, R.drawable.ic_select),
    FILTERS("filters", R.string.shortcut_cat_filters, R.drawable.ic_magicwand),
    PANELS("panels", R.string.shortcut_cat_panels, R.drawable.ic_menu),
    ANIMATION("animation", R.string.shortcut_cat_animation, R.drawable.ic_clock),
    ;

    companion object {
        fun fromId(id: String?): ShortcutCategory? = entries.firstOrNull { it.id == id }
    }
}

/** 分类内的小节标题（Krita 的树状层级在移动端压缩成段标题）。 */
enum class ShortcutSection(
    val id: String,
    val category: ShortcutCategory,
    @param:StringRes val titleRes: Int,
) {
    BRUSH_CTRL("brush_ctrl", ShortcutCategory.PAINTING, R.string.shortcut_section_brush_ctrl),
    COLOR("color", ShortcutCategory.PAINTING, R.string.shortcut_section_color),
    TOOL_SWITCH("tool_switch", ShortcutCategory.PAINTING, R.string.shortcut_section_tool_switch),

    TOOL_BRUSH("tool_brush", ShortcutCategory.TOOLS, R.string.tool_group_brush),
    TOOL_FILL("tool_fill", ShortcutCategory.TOOLS, R.string.tool_group_fill),
    TOOL_SHAPE("tool_shape", ShortcutCategory.TOOLS, R.string.tool_group_shapes),
    TOOL_SELECT("tool_select", ShortcutCategory.TOOLS, R.string.tool_group_selection),
    TOOL_TRANSFORM("tool_transform", ShortcutCategory.TOOLS, R.string.tool_group_transform),
    TOOL_OTHER("tool_other", ShortcutCategory.TOOLS, R.string.tool_group_other),

    VIEW_ZOOM("view_zoom", ShortcutCategory.VIEW, R.string.shortcut_section_view_zoom),
    VIEW_ROTATE("view_rotate", ShortcutCategory.VIEW, R.string.shortcut_section_view_rotate),
    VIEW_DISPLAY("view_display", ShortcutCategory.VIEW, R.string.shortcut_section_view_display),

    EDIT_HISTORY("edit_history", ShortcutCategory.EDIT, R.string.shortcut_section_edit_history),
    EDIT_CLIPBOARD("edit_clipboard", ShortcutCategory.EDIT, R.string.shortcut_section_edit_clipboard),
    EDIT_DOCUMENT("edit_document", ShortcutCategory.EDIT, R.string.shortcut_section_edit_document),

    LAYER_BASE("layer_base", ShortcutCategory.LAYERS, R.string.shortcut_section_layer_base),
    LAYER_STACK("layer_stack", ShortcutCategory.LAYERS, R.string.shortcut_section_layer_stack),
    LAYER_MASK("layer_mask", ShortcutCategory.LAYERS, R.string.shortcut_section_layer_mask),

    SELECT_BASE("select_base", ShortcutCategory.SELECTION, R.string.shortcut_section_select_base),
    SELECT_EDIT("select_edit", ShortcutCategory.SELECTION, R.string.shortcut_section_select_edit),

    FILTER_COLOR("filter_color", ShortcutCategory.FILTERS, R.string.filter_cat_adjust_color),
    FILTER_BLUR("filter_blur", ShortcutCategory.FILTERS, R.string.filter_cat_blur_smooth),
    FILTER_ENHANCE("filter_enhance", ShortcutCategory.FILTERS, R.string.filter_cat_enhance),
    FILTER_MAP("filter_map", ShortcutCategory.FILTERS, R.string.filter_cat_map_channels),
    FILTER_ARTISTIC("filter_artistic", ShortcutCategory.FILTERS, R.string.filter_cat_artistic),
    FILTER_DISTORT("filter_distort", ShortcutCategory.FILTERS, R.string.filter_cat_distort),

    PANEL_WINDOW("panel_window", ShortcutCategory.PANELS, R.string.shortcut_section_panel_window),

    ANIM_PLAY("anim_play", ShortcutCategory.ANIMATION, R.string.shortcut_section_anim_play),
    ANIM_FRAME("anim_frame", ShortcutCategory.ANIMATION, R.string.shortcut_section_anim_frame),
    ;

    companion object {
        fun fromId(id: String?): ShortcutSection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 长按按键时的连发策略。
 * - [NONE] 只认第一次按下（工具切换/新建图层等离散动作）
 * - [FAST] 50ms 节流连发（大小/不透明度/缩放等连续调节）
 * - [SLOW] 200ms 节流连发（撤销/重做/图层切换等重操作）
 */
enum class ShortcutRepeat { NONE, FAST, SLOW }

/**
 * 一条可绑定动作。
 *
 * @param id 稳定标识（持久化键，改动会让用户绑定失效，不要随手改）
 * @param defaultKeys 出厂按键组合（0 条 = 默认不绑定，1 条以上 = 多按键）
 */
data class ShortcutDefinition(
    val id: String,
    val category: ShortcutCategory,
    val section: ShortcutSection,
    @param:StringRes val nameRes: Int,
    val defaultKeys: List<String> = emptyList(),
    val repeat: ShortcutRepeat = ShortcutRepeat.NONE,
    /**
     * 按住按键时「临时」切到该工具，松开回到按之前的工具（Krita 的 transient tool）。
     * 只有绘画/填充/形状/选区这些可以从半路切走的工具才开，变换、文本、液化等会开面板的
     * 工具不开临时模式。
     */
    val holdTemporary: Boolean = false,
) {
    /** 主按键（第一条），用于只关心一个按键的旧调用点与展示。 */
    val defaultKey: String get() = defaultKeys.firstOrNull() ?: NO_BINDING
}

/** 未绑定用的占位文案键；实际展示统一走 [NO_BINDING]。 */
const val NO_BINDING = "无"

private fun def(
    id: String,
    category: ShortcutCategory,
    section: ShortcutSection,
    @StringRes nameRes: Int,
    vararg defaultKeys: String,
): ShortcutDefinition = ShortcutDefinition(id, category, section, nameRes, defaultKeys.toList())

private fun defRepeat(
    id: String,
    category: ShortcutCategory,
    section: ShortcutSection,
    @StringRes nameRes: Int,
    repeat: ShortcutRepeat,
    vararg defaultKeys: String,
): ShortcutDefinition = ShortcutDefinition(id, category, section, nameRes, defaultKeys.toList(), repeat)

/** 工具动作的名称与「工具」枚举共用一张映射（单测保证与 UI 的 Tool.labelRes 一致）。 */
@StringRes
fun toolShortcutNameRes(tool: Tool): Int = when (tool) {
    Tool.BRUSH -> R.string.tool_brush
    Tool.ERASER -> R.string.tool_eraser
    Tool.SMUDGE -> R.string.tool_smudge
    Tool.FILL -> R.string.tool_fill
    Tool.ENCLOSE_FILL -> R.string.tool_enclose_fill
    Tool.GRADIENT -> R.string.tool_gradient
    Tool.SHAPES -> R.string.tool_shapes
    Tool.LINE -> R.string.tool_line
    Tool.RECT -> R.string.tool_rect
    Tool.ELLIPSE -> R.string.tool_ellipse
    Tool.POLYGON -> R.string.tool_polygon
    Tool.POLYLINE -> R.string.tool_polyline
    Tool.SELECT_RECT -> R.string.tool_select_rect
    Tool.SELECT_ELLIPSE -> R.string.tool_select_ellipse
    Tool.SELECT_POLYGON -> R.string.tool_select_polygon
    Tool.LASSO -> R.string.tool_lasso
    Tool.MAGICWAND -> R.string.tool_magicwand
    Tool.SELECT_SIMILAR -> R.string.tool_select_similar
    Tool.TRANSFORM -> R.string.tool_transform
    Tool.CROP -> R.string.tool_crop
    Tool.PICKER -> R.string.tool_picker
    Tool.TEXT -> R.string.tool_text
    Tool.LIQUIFY -> R.string.tool_liquify
    Tool.MEASURE -> R.string.tool_measure
    Tool.PATH -> R.string.tool_path
    Tool.REFERENCE -> R.string.tool_reference
    Tool.SHORTCUT -> R.string.tool_quick_action
    Tool.QUICK_BRUSH -> R.string.tool_quick_brush
    Tool.QUICK_COLOR -> R.string.tool_quick_color
    Tool.QUICK_OKLCH -> R.string.tool_quick_oklch
    Tool.QUICK_LAYER -> R.string.tool_quick_layer
    Tool.DRAWING_GUIDE -> R.string.guide_title
    Tool.SYMMETRY -> R.string.tool_symmetry
    Tool.PERSPECTIVE -> R.string.tool_perspective
    Tool.SHADING_ASSIST -> R.string.shading_assist_title
    Tool.PAN -> R.string.tool_pan
    Tool.PANEL -> R.string.tool_panel
}

/** 可绑定为快捷键的「真实工具」（视图类浮窗/开关工具走 PANELS 分类）。 */
private val SHORTCUT_TOOL_SPECS: List<Pair<Tool, List<String>>> = listOf(
    // 笔刷族
    Tool.BRUSH to listOf("B"),
    Tool.ERASER to listOf("E"),
    Tool.SMUDGE to listOf("S"),
    // 填充
    Tool.FILL to listOf("G"),
    Tool.ENCLOSE_FILL to emptyList(),
    Tool.GRADIENT to listOf("LeftShift + G"),
    // 形状
    Tool.SHAPES to emptyList(),
    Tool.LINE to emptyList(),
    Tool.RECT to emptyList(),
    Tool.ELLIPSE to emptyList(),
    Tool.POLYGON to emptyList(),
    Tool.POLYLINE to emptyList(),
    Tool.PATH to emptyList(),
    // 选区工具
    Tool.SELECT_RECT to listOf("M"),
    Tool.SELECT_ELLIPSE to emptyList(),
    Tool.SELECT_POLYGON to emptyList(),
    Tool.LASSO to listOf("L"),
    Tool.MAGICWAND to listOf("W"),
    Tool.SELECT_SIMILAR to emptyList(),
    // 变换
    Tool.TRANSFORM to listOf("V", "LeftCtrl + T"),
    Tool.CROP to listOf("C"),
    // 其他
    Tool.PICKER to listOf("I"),
    Tool.TEXT to listOf("T"),
    Tool.LIQUIFY to emptyList(),
    Tool.MEASURE to emptyList(),
    Tool.PAN to emptyList(),
    Tool.PANEL to emptyList(),
)

private fun toolSectionOf(tool: Tool): ShortcutSection = when (tool.group) {
    ToolGroup.BRUSH -> ShortcutSection.TOOL_BRUSH
    ToolGroup.FILL -> ShortcutSection.TOOL_FILL
    ToolGroup.SHAPES -> ShortcutSection.TOOL_SHAPE
    ToolGroup.SELECTION -> ShortcutSection.TOOL_SELECT
    ToolGroup.TRANSFORM -> ShortcutSection.TOOL_TRANSFORM
    ToolGroup.VIEW, ToolGroup.OTHER -> ShortcutSection.TOOL_OTHER
}

/** 允许「长按临时使用」的工具族: 都是能随时切进切出的绘制/选区工具。 */
private val TEMPORARY_HOLD_TOOL_GROUPS: Set<ToolGroup> = setOf(
    ToolGroup.BRUSH,
    ToolGroup.FILL,
    ToolGroup.SHAPES,
    ToolGroup.SELECTION,
)

private val TOOL_SHORTCUTS: List<ShortcutDefinition> = SHORTCUT_TOOL_SPECS.map { (tool, keys) ->
    ShortcutDefinition(
        id = "tool_${tool.id}",
        category = ShortcutCategory.TOOLS,
        section = toolSectionOf(tool),
        nameRes = toolShortcutNameRes(tool),
        defaultKeys = keys,
        holdTemporary = tool.group in TEMPORARY_HOLD_TOOL_GROUPS,
    )
} + ShortcutDefinition(
    id = "tool_drawing_guide",
    category = ShortcutCategory.TOOLS,
    section = ShortcutSection.TOOL_OTHER,
    nameRes = R.string.guide_title,
)

/**
 * 全部可绑定动作。顺序即 UI 展示顺序（同分类内按小节聚合）。
 */
val ALL_SHORTCUT_DEFINITIONS: List<ShortcutDefinition> = buildList {
    // ---- 绘画：笔刷控制 ----
    add(defRepeat("brush_size_inc", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_size_inc, ShortcutRepeat.FAST, "]"))
    add(defRepeat("brush_size_dec", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_size_dec, ShortcutRepeat.FAST, "["))
    add(defRepeat("brush_opacity_inc", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_opacity_inc, ShortcutRepeat.FAST, "LeftCtrl + ]"))
    add(defRepeat("brush_opacity_dec", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_opacity_dec, ShortcutRepeat.FAST, "LeftCtrl + ["))
    add(defRepeat("brush_flow_inc", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_flow_inc, ShortcutRepeat.FAST))
    add(defRepeat("brush_flow_dec", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_flow_dec, ShortcutRepeat.FAST))
    add(defRepeat("brush_prev_preset", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_prev_preset, ShortcutRepeat.SLOW))
    add(defRepeat("brush_next_preset", ShortcutCategory.PAINTING, ShortcutSection.BRUSH_CTRL,
        R.string.shortcut_def_brush_next_preset, ShortcutRepeat.SLOW))

    // ---- 绘画：颜色 ----
    add(def("swap_colors", ShortcutCategory.PAINTING, ShortcutSection.COLOR,
        R.string.shortcut_def_swap_colors, "X"))
    add(def("reset_colors", ShortcutCategory.PAINTING, ShortcutSection.COLOR,
        R.string.shortcut_def_reset_colors, "D"))
    add(def("tool_color", ShortcutCategory.PAINTING, ShortcutSection.COLOR,
        R.string.shortcut_def_color, "PageUp"))

    // ---- 绘画：工具切换 ----
    add(def("toggle_eraser", ShortcutCategory.PAINTING, ShortcutSection.TOOL_SWITCH,
        R.string.shortcut_def_toggle_eraser, "PageDown"))
    add(def("eraser_mode", ShortcutCategory.PAINTING, ShortcutSection.TOOL_SWITCH,
        R.string.shortcut_def_eraser_mode))
    add(def("toggle_last_tool", ShortcutCategory.PAINTING, ShortcutSection.TOOL_SWITCH,
        R.string.shortcut_def_toggle_last_tool))
    add(def("disable_touch", ShortcutCategory.PAINTING, ShortcutSection.TOOL_SWITCH,
        R.string.shortcut_def_disable_touch))

    // ---- 工具 ----
    addAll(TOOL_SHORTCUTS)

    // ---- 视图：缩放 ----
    add(defRepeat("zoom_in", ShortcutCategory.VIEW, ShortcutSection.VIEW_ZOOM,
        R.string.shortcut_def_zoom_in, ShortcutRepeat.FAST, "LeftCtrl + =", "ZoomIn"))
    add(defRepeat("zoom_out", ShortcutCategory.VIEW, ShortcutSection.VIEW_ZOOM,
        R.string.shortcut_def_zoom_out, ShortcutRepeat.FAST, "LeftCtrl + -", "ZoomOut"))
    add(def("zoom_100", ShortcutCategory.VIEW, ShortcutSection.VIEW_ZOOM,
        R.string.shortcut_def_zoom_100))
    add(def("fit_canvas", ShortcutCategory.VIEW, ShortcutSection.VIEW_ZOOM,
        R.string.shortcut_def_fit_canvas, "LeftCtrl + 0"))

    // ---- 视图：旋转与翻转 ----
    add(def("rotate_canvas", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_rotate_canvas, "R"))
    add(def("rotate_canvas_ccw", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_rotate_ccw, "LeftShift + R"))
    add(defRepeat("rotate_fine_cw", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_rotate_fine_cw, ShortcutRepeat.FAST))
    add(defRepeat("rotate_fine_ccw", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_rotate_fine_ccw, ShortcutRepeat.FAST))
    add(def("rotate_reset", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_rotate_reset))
    add(def("flip_canvas", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_flip_canvas, "H"))
    add(def("flip_canvas_vertical", ShortcutCategory.VIEW, ShortcutSection.VIEW_ROTATE,
        R.string.shortcut_def_flip_canvas_vertical))

    // ---- 视图：显示 ----
    add(def("toggle_view_lock", ShortcutCategory.VIEW, ShortcutSection.VIEW_DISPLAY,
        R.string.shortcut_def_view_lock))
    add(def("toggle_pixel_grid", ShortcutCategory.VIEW, ShortcutSection.VIEW_DISPLAY,
        R.string.shortcut_def_pixel_grid))
    add(def("toggle_zen_mode", ShortcutCategory.VIEW, ShortcutSection.VIEW_DISPLAY,
        R.string.shortcut_def_zen_mode, "Tab"))

    // ---- 编辑：历史 ----
    add(defRepeat("undo", ShortcutCategory.EDIT, ShortcutSection.EDIT_HISTORY,
        R.string.shortcut_def_undo, ShortcutRepeat.SLOW, "LeftCtrl + Z"))
    add(defRepeat("redo", ShortcutCategory.EDIT, ShortcutSection.EDIT_HISTORY,
        R.string.shortcut_def_redo, ShortcutRepeat.SLOW, "LeftCtrl + LeftShift + Z", "LeftCtrl + Y"))

    // ---- 编辑：剪贴板与选区 ----
    add(def("cut", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_cut, "LeftCtrl + X"))
    add(def("copy", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_copy, "LeftCtrl + C"))
    add(def("paste", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_paste, "LeftCtrl + V"))
    add(def("deselect", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_deselect, "LeftCtrl + D"))
    add(def("select_all", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_select_all, "LeftCtrl + A"))
    add(def("select_all_canvas", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_select_all_canvas))
    add(def("invert_selection", ShortcutCategory.EDIT, ShortcutSection.EDIT_CLIPBOARD,
        R.string.shortcut_def_invert_selection, "LeftCtrl + LeftShift + I"))

    // ---- 编辑：文档与画布 ----
    add(def("save_document", ShortcutCategory.EDIT, ShortcutSection.EDIT_DOCUMENT,
        R.string.shortcut_def_save_document, "LeftCtrl + S"))
    add(def("canvas_adjust", ShortcutCategory.EDIT, ShortcutSection.EDIT_DOCUMENT,
        R.string.shortcut_def_canvas_adjust))
    add(def("canvas_resize", ShortcutCategory.EDIT, ShortcutSection.EDIT_DOCUMENT,
        R.string.shortcut_def_canvas_resize))

    // ---- 图层：基础 ----
    add(def("layer_new", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_new, "LeftCtrl + LeftShift + N"))
    add(def("layer_new_group", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_new_group))
    add(def("layer_duplicate", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_duplicate, "LeftCtrl + J"))
    add(def("layer_delete", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_delete, "Delete", "Backspace"))
    add(def("layer_merge_down", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_merge_down, "LeftCtrl + E"))
    add(def("layer_clear", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_clear))
    add(def("layer_toggle_vis", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_toggle_vis, "LeftCtrl + H"))
    add(def("layer_alpha_lock", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_alpha_lock))
    add(def("layer_lock", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_lock))
    add(def("layer_solo", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_solo))
    add(defRepeat("layer_prev", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_prev, ShortcutRepeat.SLOW))
    add(defRepeat("layer_next", ShortcutCategory.LAYERS, ShortcutSection.LAYER_BASE,
        R.string.shortcut_def_layer_next, ShortcutRepeat.SLOW))

    // ---- 图层：顺序与合并 ----
    add(def("layer_move_up", ShortcutCategory.LAYERS, ShortcutSection.LAYER_STACK,
        R.string.shortcut_def_layer_move_up))
    add(def("layer_move_down", ShortcutCategory.LAYERS, ShortcutSection.LAYER_STACK,
        R.string.shortcut_def_layer_move_down))
    add(def("layer_move_out", ShortcutCategory.LAYERS, ShortcutSection.LAYER_STACK,
        R.string.shortcut_def_layer_move_out))
    add(def("layer_flatten_group", ShortcutCategory.LAYERS, ShortcutSection.LAYER_STACK,
        R.string.shortcut_def_layer_flatten))
    add(def("layer_rasterize", ShortcutCategory.LAYERS, ShortcutSection.LAYER_STACK,
        R.string.shortcut_def_layer_rasterize))
    add(def("layer_stamp", ShortcutCategory.LAYERS, ShortcutSection.LAYER_STACK,
        R.string.shortcut_def_layer_stamp))

    // ---- 图层：蒙版与合成 ----
    add(def("layer_add_mask", ShortcutCategory.LAYERS, ShortcutSection.LAYER_MASK,
        R.string.shortcut_def_layer_add_mask))
    add(def("layer_add_selection_mask", ShortcutCategory.LAYERS, ShortcutSection.LAYER_MASK,
        R.string.shortcut_def_layer_add_selection_mask))
    add(def("layer_invert_mask", ShortcutCategory.LAYERS, ShortcutSection.LAYER_MASK,
        R.string.shortcut_def_layer_invert_mask))
    add(def("layer_remove_mask", ShortcutCategory.LAYERS, ShortcutSection.LAYER_MASK,
        R.string.shortcut_def_layer_remove_mask))
    add(def("layer_fill_fg", ShortcutCategory.LAYERS, ShortcutSection.LAYER_MASK,
        R.string.shortcut_def_layer_fill_fg))
    add(def("layer_to_selection", ShortcutCategory.LAYERS, ShortcutSection.LAYER_MASK,
        R.string.shortcut_def_layer_to_selection))

    // ---- 选区 ----
    add(defRepeat("selection_expand", ShortcutCategory.SELECTION, ShortcutSection.SELECT_BASE,
        R.string.shortcut_def_selection_expand, ShortcutRepeat.FAST))
    add(defRepeat("selection_contract", ShortcutCategory.SELECTION, ShortcutSection.SELECT_BASE,
        R.string.shortcut_def_selection_contract, ShortcutRepeat.FAST))
    add(def("selection_feather", ShortcutCategory.SELECTION, ShortcutSection.SELECT_BASE,
        R.string.shortcut_def_selection_feather))
    add(def("selection_smooth", ShortcutCategory.SELECTION, ShortcutSection.SELECT_BASE,
        R.string.shortcut_def_selection_smooth))
    add(def("selection_save", ShortcutCategory.SELECTION, ShortcutSection.SELECT_BASE,
        R.string.shortcut_def_selection_save))

    // ---- 选区：套索编辑 ----
    add(def("selection_lasso_undo", ShortcutCategory.SELECTION, ShortcutSection.SELECT_EDIT,
        R.string.shortcut_def_selection_lasso_undo))
    add(def("selection_lasso_finish", ShortcutCategory.SELECTION, ShortcutSection.SELECT_EDIT,
        R.string.shortcut_def_selection_lasso_finish))
    add(def("selection_lasso_cancel", ShortcutCategory.SELECTION, ShortcutSection.SELECT_EDIT,
        R.string.shortcut_def_selection_lasso_cancel))

    // ---- 滤镜：分类入口 ----
    add(def("filter_cat_color", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_cat_adjust_color))
    add(def("filter_cat_blur", ShortcutCategory.FILTERS, ShortcutSection.FILTER_BLUR,
        R.string.filter_cat_blur_smooth))
    add(def("filter_cat_enhance", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ENHANCE,
        R.string.filter_cat_enhance))
    add(def("filter_cat_map", ShortcutCategory.FILTERS, ShortcutSection.FILTER_MAP,
        R.string.filter_cat_map_channels))
    add(def("filter_cat_artistic", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ARTISTIC,
        R.string.filter_cat_artistic))
    add(def("filter_cat_distort", ShortcutCategory.FILTERS, ShortcutSection.FILTER_DISTORT,
        R.string.filter_cat_distort))

    // ---- 滤镜：常用单项 ----
    add(def("filter_hsv", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_hsv, "LeftCtrl + U"))
    add(def("filter_curves", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_curves, "LeftCtrl + M"))
    add(def("filter_levels", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_levels))
    add(def("filter_vibrance", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_vibrance))
    add(def("filter_exposure", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_exposure))
    add(def("filter_desaturate", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_desaturate))
    add(def("filter_invert", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_invert))
    add(def("filter_threshold", ShortcutCategory.FILTERS, ShortcutSection.FILTER_COLOR,
        R.string.filter_name_threshold))
    add(def("filter_blur", ShortcutCategory.FILTERS, ShortcutSection.FILTER_BLUR,
        R.string.filter_name_gaussian_blur))
    add(def("filter_motion_blur", ShortcutCategory.FILTERS, ShortcutSection.FILTER_BLUR,
        R.string.filter_name_motion_blur))
    add(def("filter_radial_blur", ShortcutCategory.FILTERS, ShortcutSection.FILTER_BLUR,
        R.string.filter_name_radial_blur))
    add(def("filter_sharpen", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ENHANCE,
        R.string.filter_name_sharpen))
    add(def("filter_emboss", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ENHANCE,
        R.string.filter_name_emboss))
    add(def("filter_sobel", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ENHANCE,
        R.string.filter_name_sobel))
    add(def("filter_gradient_map", ShortcutCategory.FILTERS, ShortcutSection.FILTER_MAP,
        R.string.filter_name_gradient_map))
    add(def("filter_lum_to_alpha", ShortcutCategory.FILTERS, ShortcutSection.FILTER_MAP,
        R.string.filter_name_lum_to_alpha))
    add(def("filter_noise", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ARTISTIC,
        R.string.filter_name_noise))
    add(def("filter_posterize", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ARTISTIC,
        R.string.filter_name_posterize))
    add(def("filter_mosaic", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ARTISTIC,
        R.string.filter_name_mosaic))
    add(def("filter_halftone", ShortcutCategory.FILTERS, ShortcutSection.FILTER_ARTISTIC,
        R.string.filter_name_halftone))
    add(def("filter_glitch", ShortcutCategory.FILTERS, ShortcutSection.FILTER_DISTORT,
        R.string.filter_name_glitch))
    add(def("filter_ripple", ShortcutCategory.FILTERS, ShortcutSection.FILTER_DISTORT,
        R.string.filter_name_ripple))
    add(def("filter_swirl", ShortcutCategory.FILTERS, ShortcutSection.FILTER_DISTORT,
        R.string.filter_name_swirl))

    // ---- 面板 ----
    add(def("panel_brush", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_brush))
    add(def("panel_layers", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_layers, "F7"))
    add(def("panel_color", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_color))
    add(def("panel_settings", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_settings))
    add(def("panel_drawing_guide", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_guide))
    add(def("toggle_quick_menu", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.stylus_action_quick_menu))
    add(def("toggle_quick_actions", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_quick_actions))
    add(def("toggle_quick_brush", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_quick_brush))
    add(def("toggle_quick_color", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_quick_color))
    add(def("toggle_quick_oklch", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_quick_oklch))
    add(def("toggle_quick_layer", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_quick_layer))
    add(def("toggle_ref_window", ShortcutCategory.PANELS, ShortcutSection.PANEL_WINDOW,
        R.string.shortcut_def_panel_reference))

    // ---- 动画 ----
    add(def("anim_play_pause", ShortcutCategory.ANIMATION, ShortcutSection.ANIM_PLAY,
        R.string.shortcut_def_anim_play_pause))
    add(def("anim_loop", ShortcutCategory.ANIMATION, ShortcutSection.ANIM_PLAY,
        R.string.shortcut_def_anim_loop))
    add(defRepeat("anim_prev_frame", ShortcutCategory.ANIMATION, ShortcutSection.ANIM_FRAME,
        R.string.shortcut_def_anim_prev_frame, ShortcutRepeat.FAST))
    add(defRepeat("anim_next_frame", ShortcutCategory.ANIMATION, ShortcutSection.ANIM_FRAME,
        R.string.shortcut_def_anim_next_frame, ShortcutRepeat.FAST))
    add(def("anim_onion", ShortcutCategory.ANIMATION, ShortcutSection.ANIM_FRAME,
        R.string.shortcut_def_anim_onion))
    add(def("anim_timeline", ShortcutCategory.ANIMATION, ShortcutSection.ANIM_FRAME,
        R.string.shortcut_def_anim_timeline))
}

private val SHORTCUT_BY_ID: Map<String, ShortcutDefinition> =
    ALL_SHORTCUT_DEFINITIONS.associateBy { it.id }

fun findShortcutDefinition(id: String?): ShortcutDefinition? = id?.let { SHORTCUT_BY_ID[it] }

// ============================================================================
// 工具键长按临时切换 (transient tool)
// ============================================================================

/** 长按判定阈值 (毫秒)；低于它仍按"单击 = 永久切换工具"处理。 */
const val TEMPORARY_TOOL_HOLD_MS = 280L

/**
 * 松开工具键时是否要回到按住之前的工具（纯函数，便于单测）。
 *
 * 三个条件缺一不可：按住够久、确实记过临时工具、当前仍是那把临时工具
 * （期间被别的操作改掉就不抢用户的显式切换）。
 */
fun shouldRevertTemporaryTool(
    heldMs: Long,
    currentToolId: String?,
    temporaryToolId: String?,
): Boolean {
    if (temporaryToolId == null) return false
    if (heldMs < TEMPORARY_TOOL_HOLD_MS) return false
    return currentToolId == temporaryToolId
}

// ============================================================================
// 按键组合规范化与匹配
// ============================================================================

private fun isNoBinding(raw: String): Boolean {
    val t = raw.trim()
    return t.isEmpty() || t == NO_BINDING || t.equals("none", ignoreCase = true)
}

/**
 * 规范化快捷键组合字符串，统一修饰键名称并排序，消除 "LeftCtrl + Z" 与 "Ctrl + Z"、
 * "Ctrl + Shift + Z" 与 "Shift + Ctrl + Z" 的格式差异。
 */
fun normalizeShortcut(shortcut: String): String {
    val trimmed = shortcut.trim()
    if (isNoBinding(trimmed)) return ""
    if (trimmed.equals("Space(长按)", ignoreCase = true)) return "SPACE(长按)"

    val tokens = trimmed.split("+").map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ""

    var hasCtrl = false
    var hasAlt = false
    var hasShift = false
    val mainKeys = mutableListOf<String>()

    for (token in tokens) {
        val lower = token.lowercase()
        when (lower) {
            "ctrl", "leftctrl", "rightctrl", "control", "meta", "leftmeta", "rightmeta", "command", "win" -> hasCtrl = true
            "alt", "leftalt", "rightalt", "option" -> hasAlt = true
            "shift", "leftshift", "rightshift" -> hasShift = true
            else -> mainKeys.add(token.uppercase())
        }
    }

    val parts = mutableListOf<String>()
    if (hasCtrl) parts.add("CTRL")
    if (hasAlt) parts.add("ALT")
    if (hasShift) parts.add("SHIFT")
    parts.addAll(mainKeys)
    return parts.joinToString("+")
}

/** 判断两个快捷键组合在逻辑上是否等价 (忽略大小写、左右修饰键与修饰键顺序差异)。 */
fun isShortcutMatch(shortcutA: String, shortcutB: String): Boolean {
    val normA = normalizeShortcut(shortcutA)
    val normB = normalizeShortcut(shortcutB)
    if (normA.isEmpty() || normB.isEmpty()) return false
    return normA == normB
}

/** 按住式绑定（Space 长按平移）在匹配时只对应裸 Space。 */
fun isHoldBinding(binding: String): Boolean = binding.trim().equals("Space(长按)", ignoreCase = true)

/** 一条绑定是否命中某次按键。 */
fun shortcutBindingMatchesKey(binding: String, keyStr: String): Boolean {
    if (isNoBinding(binding)) return false
    if (isHoldBinding(binding)) return isShortcutMatch(keyStr, "Space")
    return isShortcutMatch(binding, keyStr)
}

/** 两组组合键是否有重叠（用于冲突检测，含按住式绑定的特例）。 */
fun shortcutKeysOverlap(a: String, b: String): Boolean {
    if (isNoBinding(a) || isNoBinding(b)) return false
    if (isHoldBinding(a)) return isShortcutMatch(b, "Space")
    if (isHoldBinding(b)) return isShortcutMatch(a, "Space")
    return isShortcutMatch(a, b)
}

// ============================================================================
// 绑定集合 (actionId -> 按键列表)
// ============================================================================

/** 取值语义：键不存在 = 用出厂默认；存在且为空 = 用户显式解绑；否则为自定义按键。 */
fun resolveShortcutKeys(
    bindings: Map<String, List<String>>,
    definition: ShortcutDefinition,
): List<String> = bindings[definition.id] ?: definition.defaultKeys

fun resolveShortcutKeys(bindings: Map<String, List<String>>, id: String): List<String> =
    findShortcutDefinition(id)?.let { resolveShortcutKeys(bindings, it) } ?: emptyList()

/** 该动作的绑定是否被用户改过（含显式解绑）。 */
fun isShortcutCustomized(bindings: Map<String, List<String>>, definition: ShortcutDefinition): Boolean {
    val current = bindings[definition.id] ?: return false
    if (current.size != definition.defaultKeys.size) return true
    return current.indices.any { !isShortcutMatch(current[it], definition.defaultKeys[it]) }
}

/** 与目标按键冲突的其他动作。 */
fun findShortcutConflicts(
    bindings: Map<String, List<String>>,
    id: String,
    keys: List<String>,
): List<ShortcutDefinition> {
    val cleanKeys = keys.filter { !isNoBinding(it) }
    if (cleanKeys.isEmpty()) return emptyList()
    return ALL_SHORTCUT_DEFINITIONS.filter { other ->
        other.id != id && resolveShortcutKeys(bindings, other).any { bound ->
            cleanKeys.any { shortcutKeysOverlap(bound, it) }
        }
    }
}

/** 当前绑定表里所有互相冲突的动作 id。 */
fun findConflictingShortcutIds(bindings: Map<String, List<String>>): Set<String> {
    val assigned = ALL_SHORTCUT_DEFINITIONS.flatMap { def ->
        resolveShortcutKeys(bindings, def).filter { !isNoBinding(it) }.map { def.id to it }
    }
    val conflicting = mutableSetOf<String>()
    for (i in assigned.indices) {
        for (j in i + 1 until assigned.size) {
            if (assigned[i].first == assigned[j].first) continue
            if (shortcutKeysOverlap(assigned[i].second, assigned[j].second)) {
                conflicting.add(assigned[i].first)
                conflicting.add(assigned[j].first)
            }
        }
    }
    return conflicting
}

/** 搜索过滤：动作名或已绑定按键命中查询词。 */
fun shortcutMatchesQuery(
    definition: ShortcutDefinition,
    displayName: String,
    keys: List<String>,
    query: String,
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (displayName.contains(q, ignoreCase = true)) return true
    if (definition.id.contains(q, ignoreCase = true)) return true
    return keys.any { key ->
        key.contains(q, ignoreCase = true) || normalizeShortcut(key).contains(q.replace(" ", "").uppercase())
    }
}

// ============================================================================
// 持久化 / 方案交换编解码
//
// 用制表符分隔的纯文本：一行一个动作 `<id>\t<按键1>\t<按键2>`。
// 不用 JSON 是为了让 model 层保持零 Android 依赖、可在 JVM 单测里直接往返验证；
// 以 `#` 开头的行视为注释/方案头，加载时跳过。
// ============================================================================

const val SHORTCUT_SCHEME_HEADER = "#ReveriePaint Shortcuts v1"

/** 单个动作最多挂几条按键（主按键 + 备选按键，与 Krita 的 UI 保持一致）。 */
const val MAX_SHORTCUT_KEYS_PER_ACTION = 2

fun encodeShortcutBindings(bindings: Map<String, List<String>>): String {
    val body = bindings.entries
        .sortedBy { it.key }
        .joinToString("\n") { entry ->
            (listOf(entry.key) + entry.value.map { it.trim() }.filter { it.isNotEmpty() })
                .joinToString("\t")
        }
    return if (body.isEmpty()) "" else "$SHORTCUT_SCHEME_HEADER\n$body"
}

/**
 * 解析绑定文本。[bindings] 中只保留当前版本认识的 id（跨版本容错），
 * 单行动作内按键去重并丢弃占位符。
 */
fun decodeShortcutBindings(text: String?): Map<String, List<String>> {
    if (text.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, List<String>>()
    for (line in text.split('\n')) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val cells = trimmed.split('\t')
        val id = cells.firstOrNull()?.trim().orEmpty()
        if (id.isEmpty() || findShortcutDefinition(id) == null) continue
        val keys = cells.drop(1)
            .map { it.trim() }
            .filter { !isNoBinding(it) }
            .distinctBy { normalizeShortcut(it) }
            .take(MAX_SHORTCUT_KEYS_PER_ACTION)
        out[id] = keys
    }
    return out
}

/** 从外部文本导入的方案：与当前绑定合并（导入项覆盖同名动作，其余保持不动）。 */
fun mergeShortcutScheme(
    current: Map<String, List<String>>,
    imported: Map<String, List<String>>,
): Map<String, List<String>> {
    if (imported.isEmpty()) return current
    val merged = LinkedHashMap(current)
    merged.putAll(imported)
    return merged
}

// ============================================================================
// 快捷键方案预设（对标 Krita 的方案下拉框：Krita / Photoshop / CSP）
//
// 预设只列出与出厂默认不同的动作；未列出的动作保持出厂值。
// [ShortcutPreset.ONE_HAND] 额外清掉所有 Ctrl 组合，适合平板外接键盘。
// ============================================================================

enum class ShortcutPreset(
    val id: String,
    @param:StringRes val labelRes: Int,
    val overrides: Map<String, List<String>> = emptyMap(),
    val dropCtrlDefaults: Boolean = false,
) {
    DEFAULT("default", R.string.shortcut_preset_default),

    PHOTOSHOP(
        "photoshop",
        R.string.shortcut_preset_photoshop,
        overrides = mapOf(
            // PS：H 是抓手，视图翻转没有默认键
            "tool_pan" to listOf("H"),
            "flip_canvas" to emptyList(),
            // PS：G 在渐变/油漆桶之间轮换，这里把渐变放 G、填充让位
            "tool_gradient" to listOf("G"),
            "tool_fill" to emptyList(),
            // PS：, . 轮换画笔预设
            "brush_prev_preset" to listOf(","),
            "brush_next_preset" to listOf("."),
            "zoom_100" to listOf("LeftCtrl + Alt + 0"),
            "select_all" to listOf("LeftCtrl + A"),
            "invert_selection" to listOf("LeftCtrl + LeftShift + I"),
            "cut" to listOf("LeftCtrl + X"),
            "copy" to listOf("LeftCtrl + C"),
            "paste" to listOf("LeftCtrl + V"),
        ),
    ),

    CLIP_STUDIO(
        "clip_studio",
        R.string.shortcut_preset_csp,
        overrides = mapOf(
            // CSP：P 画笔、Y 铅笔（这里没有铅笔，用 Y 切橡皮以外的常用笔）
            "tool_brush" to listOf("P"),
            "redo" to listOf("LeftCtrl + Y"),
            "brush_prev_preset" to listOf("LeftShift + ["),
            "brush_next_preset" to listOf("LeftShift + ]"),
            "zoom_100" to listOf("LeftCtrl + 1"),
        ),
    ),

    ONE_HAND(
        "one_hand",
        R.string.shortcut_preset_one_hand,
        overrides = mapOf(
            "undo" to listOf("Z"),
            "redo" to listOf("LeftShift + Z"),
            "zoom_in" to listOf("="),
            "zoom_out" to listOf("-"),
            "fit_canvas" to listOf("0"),
            "deselect" to listOf("LeftShift + D"),
            "select_all" to listOf("LeftShift + A"),
            "invert_selection" to listOf("LeftShift + I"),
            "cut" to listOf("LeftShift + X"),
            "copy" to listOf("LeftShift + C"),
            "paste" to listOf("LeftShift + V"),
            "save_document" to listOf("LeftShift + S"),
            "layer_new" to listOf("N"),
            "layer_duplicate" to listOf("LeftShift + J"),
            "layer_merge_down" to listOf("LeftShift + E"),
        ),
        dropCtrlDefaults = true,
    ),
    ;

    companion object {
        fun fromId(id: String?): ShortcutPreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 生成某预设对应的完整覆盖表（相对出厂默认）。 */
fun shortcutPresetOverrides(preset: ShortcutPreset): Map<String, List<String>> {
    val result = LinkedHashMap<String, List<String>>()
    result.putAll(preset.overrides)
    if (preset.dropCtrlDefaults) {
        for (definition in ALL_SHORTCUT_DEFINITIONS) {
            if (result.containsKey(definition.id)) continue
            val kept = definition.defaultKeys.filterNot { normalizeShortcut(it).startsWith("CTRL") }
            if (kept.size != definition.defaultKeys.size) result[definition.id] = kept
        }
    }
    return result
}
