/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.CanvasAdjustMode
import com.reverie.paint.model.LayerMaskType
import com.reverie.paint.model.Tool

/**
 * 快捷键动作分发。
 *
 * 目录与默认按键见 model/ShortcutCatalog.kt；这里按域拆成若干小 when，
 * 每域返回「是否消费了该 id」，[executeShortcutAction] 顺序试探。
 * 新增可绑定动作时只需：目录登记一条 + 在这里的某个域加一个分支。
 */
/**
 * 执行快捷键动作。
 *
 * @param ephemeral 该动作是否由手写笔手势唤起。仅影响会打开悬浮面板的动作
 *                  (目前是快捷调色盘): 手势唤起的面板可标记为临时态，是否落笔收起
 *                  再由用户偏好 [PaintViewModel.quickColorAutoHideOnStroke] 决定。
 */
internal fun PaintViewModel.executeShortcutAction(id: String, ephemeral: Boolean = false) {
    if (executeBrushShortcutAction(id, ephemeral)) return
    if (executeToolShortcutAction(id)) return
    if (executeViewShortcutAction(id)) return
    if (executeEditShortcutAction(id)) return
    if (executeLayerShortcutAction(id)) return
    if (executeSelectionShortcutAction(id)) return
    if (executeFilterShortcutAction(id)) return
    if (executePanelShortcutAction(id, ephemeral)) return
    if (executeAnimationShortcutAction(id)) return
}

// ---- 绘画：笔刷与颜色 ----

private fun PaintViewModel.executeBrushShortcutAction(id: String, ephemeral: Boolean = false): Boolean {
    when (id) {
        "brush_size_inc" -> {
            val maxL = effectiveBrushMaxSize
            updateBrushSize((brushSize * 1.25).coerceAtMost(maxL))
        }

        "brush_size_dec" -> {
            val minL = brushMinSizeLimit.coerceAtLeast(0.5)
            updateBrushSize((brushSize / 1.25).coerceAtLeast(minL))
        }

        "brush_opacity_inc" -> updateBrushOpacity((brushOpacity + 0.1).coerceAtMost(1.0))
        "brush_opacity_dec" -> updateBrushOpacity((brushOpacity - 0.1).coerceAtLeast(0.01))
        "brush_flow_inc" -> updateBrushFlow((brushFlow + 0.1).coerceAtMost(1.0))
        "brush_flow_dec" -> updateBrushFlow((brushFlow - 0.1).coerceAtLeast(0.01))
        "brush_prev_preset" -> stepBrushPreset(-1)
        "brush_next_preset" -> stepBrushPreset(1)
        "swap_colors" -> swapColors()
        "reset_colors" -> {
            updateBrushColor("#000000")
            updateBrushSecondaryColor("#ffffff")
            showActionToast(R.string.shortcut_toast_colors_reset, R.drawable.ic_palette)
        }

        "tool_color" -> toggleQuickColor(atPointer = true, ephemeral = ephemeral)
        "toggle_eraser" -> {
            if (currentToolId == "eraser") applyTool("brush") else applyTool("eraser")
        }

        "eraser_mode" -> toggleEraserMode()
        "toggle_last_tool" -> {
            val t = lastToolId
            if (t.isNotEmpty() && t != currentToolId) applyTool(t)
        }

        "disable_touch" -> toggleCanvasTouchDisabled()
        else -> return false
    }
    return true
}

/** 循环切换笔刷预设（原生索引顺序，与画笔面板的顺序一致）。 */
private fun PaintViewModel.stepBrushPreset(delta: Int) {
    val list = brushPresets
    if (list.isEmpty()) return
    val pos = list.indexOfFirst { it.index == brushPresetIndex }
    val nextPos = when {
        pos < 0 -> 0
        else -> ((pos + delta) % list.size + list.size) % list.size
    }
    val preset = list[nextPos]
    selectBrushPreset(preset.index)
    showActionToast(preset.name, R.drawable.ic_brush)
}

// ---- 工具 ----

private fun PaintViewModel.togglePanTool() {
    if (currentToolId == "pan") {
        applyTool(if (lastToolId.isNotEmpty() && lastToolId != "pan") lastToolId else "brush")
    } else {
        applyTool("pan")
    }
}

/** 工具 id -> Tool 的查表 (按键路径上不再拼字符串) */
private val TOOL_BY_SHORTCUT_ID: Map<String, Tool> =
    Tool.entries.associateBy { "tool_${it.id}" }

private fun PaintViewModel.executeToolShortcutAction(id: String): Boolean {
    if (id == "tool_drawing_guide") {
        requestUiCommand(UI_CMD_TOGGLE_DRAWING_GUIDE)
        return true
    }
    // 抓手是"按住/再按一次切回上一工具"的开关语义, 与快捷面板的 PAN 保持一致
    if (id == "pan_canvas") {
        togglePanTool()
        return true
    }
    val tool = TOOL_BY_SHORTCUT_ID[id] ?: return false
    when (tool) {
        Tool.PAN -> togglePanTool()
        // 临时吸管: 取一次色就回到之前的工具 (数位笔「吸管取色」动作也走这条 id)
        Tool.PICKER -> {
            applyTool("picker")
            isTemporaryPicker = true
        }
        // 视图类工具不进目录（走 PANELS），其余一律走统一的工具切换入口
        Tool.REFERENCE, Tool.SHORTCUT, Tool.QUICK_BRUSH, Tool.QUICK_COLOR,
        Tool.QUICK_OKLCH, Tool.QUICK_LAYER, Tool.DRAWING_GUIDE,
        Tool.SYMMETRY, Tool.PERSPECTIVE, Tool.SHADING_ASSIST,
        -> return false

        else -> applyTool(tool.id)
    }
    return true
}

// ---- 视图 ----

private fun PaintViewModel.executeViewShortcutAction(id: String): Boolean {
    when (id) {
        "zoom_in" -> requestUiCommand("zoom_in")
        "zoom_out" -> requestUiCommand("zoom_out")
        "zoom_100" -> requestUiCommand(UI_CMD_ZOOM_100)
        "fit_canvas" -> requestUiCommand("reset_view")
        "rotate_canvas" -> requestUiCommand("rotate_cw")
        "rotate_canvas_ccw" -> requestUiCommand(UI_CMD_ROTATE_CCW_90)
        "rotate_fine_cw" -> requestUiCommand(UI_CMD_ROTATE_FINE_CW)
        "rotate_fine_ccw" -> requestUiCommand(UI_CMD_ROTATE_FINE_CCW)
        "rotate_reset" -> requestUiCommand(UI_CMD_ROTATE_RESET)
        "flip_canvas" -> toggleViewFlipHorizontal()
        "flip_canvas_vertical" -> toggleViewFlipVertical()
        "toggle_view_lock" -> toggleViewTransformLocked()
        "toggle_pixel_grid" -> updatePixelGridEnabled(!pixelGridEnabled)
        "toggle_zen_mode" -> requestUiCommand(UI_CMD_TOGGLE_ZEN)
        else -> return false
    }
    return true
}

// ---- 编辑：历史 / 剪贴板 / 文档 ----

private fun PaintViewModel.executeEditShortcutAction(id: String): Boolean {
    when (id) {
        "undo" -> undo()
        "redo" -> redo()
        "cut" -> editCanvasClipboard(cut = true)
        "copy" -> editCanvasClipboard()
        "paste" -> editCanvasClipboard(paste = true)
        "deselect" -> clearSelectionAction()
        "select_all" -> selectAllAction()
        "select_all_canvas" -> selectAllCanvasAction()
        "invert_selection" -> invertSelectionAction()
        "save_document" -> saveProject(docName)
        "canvas_adjust" -> enterCanvasAdjustMode(CanvasAdjustMode.CROP_EXPAND)
        "canvas_resize" -> enterCanvasAdjustMode(CanvasAdjustMode.RESAMPLE_SCALE)
        else -> return false
    }
    return true
}

// ---- 图层 ----

private fun PaintViewModel.currentLayerUi() = layers.firstOrNull { it.index == currentLayerIndex }

private fun PaintViewModel.executeLayerShortcutAction(id: String): Boolean {
    when (id) {
        "layer_new" -> addLayer()
        "layer_new_group" -> addGroupLayer()
        "layer_duplicate" -> copyLayer(currentLayerIndex)
        "layer_delete" -> removeLayer(currentLayerIndex)
        "layer_merge_down" -> mergeDown(currentLayerIndex)
        "layer_clear" -> clearLayer(currentLayerIndex)
        "layer_toggle_vis" -> toggleLayerVisible(currentLayerIndex)
        // 用 UI 镜像读当前状态, 避免在按键路径上额外做一次 JNI 读取 (与快捷面板 ALPHA_LOCK 一致)
        "layer_alpha_lock" -> {
            val layer = currentLayerUi()
            if (layer != null && !layer.isBackground && layer.nodeType != 3) {
                setLayerAlphaLocked(layer.index, !layer.alphaLocked)
            }
        }

        "layer_lock" -> {
            val layer = currentLayerUi()
            if (layer != null && !layer.isBackground) {
                setLayerLocked(layer.index, !layer.locked)
            }
        }
        "layer_solo" -> soloLayer(currentLayerIndex)
        "layer_prev" -> {
            if (layers.isNotEmpty()) {
                setCurrentLayer((currentLayerIndex - 1).coerceIn(0, layers.lastIndex))
            }
            showActionToast(R.string.stylus_action_layer_prev, R.drawable.ic_layers)
        }

        "layer_next" -> {
            if (layers.isNotEmpty()) {
                setCurrentLayer((currentLayerIndex + 1).coerceIn(0, layers.lastIndex))
            }
            showActionToast(R.string.stylus_action_layer_next, R.drawable.ic_layers)
        }

        "layer_move_up" -> moveLayerUp(currentLayerIndex)
        "layer_move_down" -> moveLayerDown(currentLayerIndex)
        "layer_move_out" -> moveLayerOut(currentLayerIndex)
        "layer_flatten_group" -> flattenGroup(currentLayerIndex)
        "layer_rasterize" -> rasterizeLayer(currentLayerIndex)
        "layer_stamp" -> stampVisibleLayers()
        "layer_add_mask" -> addMaskToLayer(currentLayerIndex, LayerMaskType.TRANSPARENCY)
        "layer_add_selection_mask" -> addMaskToLayer(currentLayerIndex, LayerMaskType.SELECTION)
        "layer_invert_mask" -> invertMask(currentLayerIndex)
        "layer_remove_mask" -> removeMask(currentLayerIndex)
        "layer_fill_fg" -> fillLayerForeground(currentLayerIndex)
        "layer_to_selection" -> selectionFromLayer(currentLayerIndex, selectionMode)
        else -> return false
    }
    return true
}

// ---- 选区 ----

private fun PaintViewModel.executeSelectionShortcutAction(id: String): Boolean {
    when (id) {
        // 固定步长的渐进调整: 连按可逐步扩大/收缩, 与选区属性面板的滑杆同一套引擎入口
        "selection_expand" -> expandSelection(SELECTION_STEP_PX)
        "selection_contract" -> contractSelection(SELECTION_STEP_PX)
        "selection_feather" -> featherSelection(SELECTION_FEATHER_PX)
        "selection_smooth" -> smoothSelection(SELECTION_STEP_PX)
        "selection_save" -> saveCurrentSelectionAction(null)
        "selection_lasso_undo" -> undoLassoPoint()
        "selection_lasso_finish" -> finishLassoMulti()
        "selection_lasso_cancel" -> cancelLassoMulti()
        else -> return false
    }
    return true
}

private const val SELECTION_STEP_PX = 4
private const val SELECTION_FEATHER_PX = 8

// ---- 滤镜 ----

/** 分类入口 (打开滤镜页并预选分类) + 常用单项 (直接进入该滤镜的调节)。 */
private fun PaintViewModel.executeFilterShortcutAction(id: String): Boolean {
    when (id) {
        "filter_cat_color" -> {
            requestUiCommand("open_filter:color")
            return true
        }

        "filter_cat_blur" -> {
            requestUiCommand("open_filter:blur")
            return true
        }

        "filter_cat_enhance" -> {
            requestUiCommand("open_filter:enhance")
            return true
        }

        "filter_cat_map" -> {
            requestUiCommand("open_filter:map")
            return true
        }

        "filter_cat_artistic" -> {
            requestUiCommand("open_filter:artistic")
            return true
        }

        "filter_cat_distort" -> {
            requestUiCommand("open_filter:distort")
            return true
        }
    }
    val filterId = SHORTCUT_FILTER_IDS[id] ?: return false
    requestUiCommand("$UI_CMD_FILTER_ITEM_PREFIX$filterId")
    return true
}

/** 动作 id 与引擎滤镜编号 (LayerFiltersPage 的 FilterItemDef.id) 的映射。 */
private val SHORTCUT_FILTER_IDS: Map<String, Int> = mapOf(
    "filter_hsv" to 0,
    "filter_curves" to 13,
    "filter_levels" to 14,
    "filter_vibrance" to 28,
    "filter_exposure" to 24,
    "filter_desaturate" to 12,
    "filter_invert" to 6,
    "filter_threshold" to 16,
    "filter_blur" to 2,
    "filter_motion_blur" to 3,
    "filter_radial_blur" to 22,
    "filter_sharpen" to 4,
    "filter_emboss" to 9,
    "filter_sobel" to 8,
    "filter_gradient_map" to 30,
    "filter_lum_to_alpha" to 7,
    "filter_noise" to 10,
    "filter_posterize" to 17,
    "filter_mosaic" to 5,
    "filter_halftone" to 23,
    "filter_glitch" to 11,
    "filter_ripple" to 31,
    "filter_swirl" to 32,
)

// ---- 面板 ----

private fun PaintViewModel.executePanelShortcutAction(id: String, ephemeral: Boolean = false): Boolean {
    when (id) {
        "panel_brush" -> requestUiCommand(UI_CMD_TOGGLE_BRUSH_PANEL)
        "panel_layers" -> requestUiCommand(UI_CMD_TOGGLE_LAYER_PANEL)
        "panel_color" -> requestUiCommand(UI_CMD_TOGGLE_COLOR_PANEL)
        "panel_settings" -> requestUiCommand(UI_CMD_TOGGLE_SETTINGS_PANEL)
        "panel_drawing_guide" -> requestUiCommand(UI_CMD_TOGGLE_DRAWING_GUIDE)
        // 旧 id (数位笔动作 "快捷菜单"): 一直是画布上的滑出快捷菜单, 不能改语义
        "toggle_quick_menu" -> requestUiCommand("toggle_edit_menu")
        "toggle_quick_actions" -> {
            quickActionWindowOpen = !quickActionWindowOpen
            persistQuickActionsState()
        }

        "toggle_quick_brush" -> {
            quickBrushWindowOpen = !quickBrushWindowOpen
            persistQuickBrushState()
        }

        "toggle_quick_color" -> toggleQuickColor(atPointer = true, ephemeral = ephemeral)
        "toggle_quick_oklch" -> toggleQuickOklch(atPointer = true)
        "toggle_quick_layer" -> toggleQuickLayer()
        "toggle_ref_window" -> {
            referenceWindowOpen = !referenceWindowOpen
            persistReferenceState()
            showActionToast(R.string.stylus_action_ref_window, R.drawable.ic_reference)
        }

        else -> return false
    }
    return true
}

// ---- 动画 ----

private fun PaintViewModel.executeAnimationShortcutAction(id: String): Boolean {
    when (id) {
        "anim_play_pause" -> {
            if (!anim.enabled) return true
            animationTogglePlay()
        }

        "anim_prev_frame" -> {
            if (!anim.enabled) return true
            animationStepFrame(-1)
        }

        "anim_next_frame" -> {
            if (!anim.enabled) return true
            animationStepFrame(1)
        }

        "anim_onion" -> {
            if (!anim.enabled) return true
            anim.onionSkin = !anim.onionSkin
            animationApplyOnionSkin()
            showActionToast(
                if (anim.onionSkin) R.string.shortcut_toast_onion_on else R.string.shortcut_toast_onion_off,
                R.drawable.ic_clock,
            )
        }

        "anim_loop" -> {
            if (!anim.enabled) return true
            anim.loopPlayback = !anim.loopPlayback
            showActionToast(
                if (anim.loopPlayback) R.string.anim_loop else R.string.anim_single_play,
                R.drawable.ic_clock,
            )
        }

        "anim_timeline" -> {
            if (!anim.enabled) return true
            anim.panelOpen = !anim.panelOpen
        }

        else -> return false
    }
    return true
}
