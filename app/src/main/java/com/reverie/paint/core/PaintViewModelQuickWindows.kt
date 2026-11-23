/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.*
import com.reverie.paint.core.PaintViewModel.FloatingGrip

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.persistQuickActionsState() {
    if (!isAppContextReady()) return
    try {
        if (isLandscapeMode) {
            quickActionWindowX_land = quickActionWindowX
            quickActionWindowY_land = quickActionWindowY
        } else {
            quickActionWindowX_port = quickActionWindowX
            quickActionWindowY_port = quickActionWindowY
        }
        val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
        p.putBoolean("quick_action_open", quickActionWindowOpen)
        p.putFloat("quick_action_x_land", quickActionWindowX_land)
        p.putFloat("quick_action_y_land", quickActionWindowY_land)
        p.putFloat("quick_action_x_port", quickActionWindowX_port)
        p.putFloat("quick_action_y_port", quickActionWindowY_port)
        p.putFloat("quick_action_x", quickActionWindowX)
        p.putFloat("quick_action_y", quickActionWindowY)
        p.putBoolean("quick_action_collapsed", quickActionCollapsed)
        p.putString("quick_action_layout", quickActionsConfig.layoutMode.id)
        p.putBoolean("quick_action_show_labels", quickActionsConfig.showLabels)
        val actionsStr = quickActionsConfig.actions.joinToString(",") { it.id }
        p.putString("quick_action_actions", actionsStr)
        p.apply()
    } catch (_: Exception) {}
}

internal fun PaintViewModel.persistQuickBrushState() {
    if (!isAppContextReady()) return
    try {
        if (isLandscapeMode) {
            quickBrushWindowX_land = quickBrushWindowX
            quickBrushWindowY_land = quickBrushWindowY
            quickBrushOrientation_land = quickBrushOrientation
        } else {
            quickBrushWindowX_port = quickBrushWindowX
            quickBrushWindowY_port = quickBrushWindowY
            quickBrushOrientation_port = quickBrushOrientation
        }
        val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
        p.putBoolean("quick_brush_open", quickBrushWindowOpen)
        p.putFloat("quick_brush_x_land", quickBrushWindowX_land)
        p.putFloat("quick_brush_y_land", quickBrushWindowY_land)
        p.putString("quick_brush_orientation_land", quickBrushOrientation_land)
        p.putFloat("quick_brush_x_port", quickBrushWindowX_port)
        p.putFloat("quick_brush_y_port", quickBrushWindowY_port)
        p.putString("quick_brush_orientation_port", quickBrushOrientation_port)
        p.putFloat("quick_brush_x", quickBrushWindowX)
        p.putFloat("quick_brush_y", quickBrushWindowY)
        p.putBoolean("quick_brush_collapsed", quickBrushCollapsed)
        p.putString("quick_brush_orientation", quickBrushOrientation)
        p.putInt("quick_brush_max_length", quickBrushMaxLength)
        if (brushIdPrefsLoaded) {
            p.putString(com.reverie.paint.core.brushstore.BrushIdPrefs.QUICK_IDS, com.reverie.paint.core.brushstore.BrushIdPrefs.encodeList(quickBrushOrder))
        }
        p.apply()
    } catch (_: Exception) {}
}

internal fun PaintViewModel.updateQuickColorFollowsStylus(enabled: Boolean) {
    quickColorFollowsStylus = enabled
    persistQuickColorState()
}

internal fun PaintViewModel.updateQuickColorAutoHideOnStroke(enabled: Boolean) {
    quickColorAutoHideOnStroke = enabled
    persistQuickColorState()
}

internal fun PaintViewModel.persistQuickColorState() {
    if (!isAppContextReady()) return
    try {
        if (isLandscapeMode) {
            quickColorWindowX_land = quickColorWindowX
            quickColorWindowY_land = quickColorWindowY
        } else {
            quickColorWindowX_port = quickColorWindowX
            quickColorWindowY_port = quickColorWindowY
        }
        val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
        p.putBoolean("quick_color_open", quickColorWindowOpen && !quickColorEphemeral)
        p.putBoolean("quick_color_follows_stylus", quickColorFollowsStylus)
        p.putBoolean("quick_color_auto_hide_on_stroke", quickColorAutoHideOnStroke)
        p.putFloat("quick_color_x_land", quickColorWindowX_land)
        p.putFloat("quick_color_y_land", quickColorWindowY_land)
        p.putFloat("quick_color_x_port", quickColorWindowX_port)
        p.putFloat("quick_color_y_port", quickColorWindowY_port)
        p.putFloat("quick_color_x", quickColorWindowX)
        p.putFloat("quick_color_y", quickColorWindowY)
        p.putBoolean("quick_color_collapsed", quickColorCollapsed)
        p.putInt("quick_color_tab", quickColorTab)
        p.apply()
    } catch (_: Exception) {}
}

/**
 * 开关快捷调色盘。
 *
 * @param atPointer 首次兜底时是否以笔尖为参照
 * @param ephemeral 是否为笔手势唤起的临时态（是否落笔收起再由用户偏好决定）
 */
internal fun PaintViewModel.toggleQuickColor(atPointer: Boolean = false, ephemeral: Boolean = false) {
    if (quickColorWindowOpen) {
        quickColorWindowOpen = false
        quickColorEphemeral = false
    } else {
        // 随笔尖弹出(默认) → 每次唤出都定位到笔尖附近，便于就近取色；
        // 固定模式 → 沿用上次拖动位置；从未定位过时仍按笔尖兜底，避免落在屏幕角落。
        val anchoredToPointer = atPointer &&
            (quickColorFollowsStylus || isFloatingWindowUnpositioned(quickColorWindowX, quickColorWindowY))
        if (anchoredToPointer) {
            lastPointerScreenPosition?.let { pos ->
                quickColorWindowX = (pos.x - 110f).coerceAtLeast(0f)
                quickColorWindowY = (pos.y - 120f).coerceAtLeast(0f)
            }
        }
        quickColorEphemeral = ephemeral
        quickColorWindowOpen = true
    }
    persistQuickColorState()
}

/**
 * 收起「临时态」快捷调色盘(笔手势唤起的那一个)。
 *
 * 由落笔触发，但**是否真的收起取决于用户偏好** [quickColorAutoHideOnStroke]
 * (默认关闭) —— 很多用户要把面板留在画布上随时取色。
 * 常驻态(工具栏打开)始终不受影响。
 */
internal fun PaintViewModel.dismissEphemeralQuickColor() {
    if (quickColorWindowOpen && quickColorEphemeral && quickColorAutoHideOnStroke) {
        toggleQuickColor()
    }
}

internal fun PaintViewModel.swapColors() {
    val c1 = brushColor
    val c2 = brushSecondaryColor
    updateBrushColor(c2)
    updateBrushSecondaryColor(c1)
}

internal fun PaintViewModel.persistQuickOklchState() {
    if (!isAppContextReady()) return
    try {
        if (isLandscapeMode) {
            quickOklchWindowX_land = quickOklchWindowX
            quickOklchWindowY_land = quickOklchWindowY
        } else {
            quickOklchWindowX_port = quickOklchWindowX
            quickOklchWindowY_port = quickOklchWindowY
        }
        val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
        p.putBoolean("quick_oklch_open", quickOklchWindowOpen)
        p.putFloat("quick_oklch_x_land", quickOklchWindowX_land)
        p.putFloat("quick_oklch_y_land", quickOklchWindowY_land)
        p.putFloat("quick_oklch_x_port", quickOklchWindowX_port)
        p.putFloat("quick_oklch_y_port", quickOklchWindowY_port)
        p.putFloat("quick_oklch_x", quickOklchWindowX)
        p.putFloat("quick_oklch_y", quickOklchWindowY)
        p.putBoolean("quick_oklch_collapsed", quickOklchCollapsed)
        p.apply()
    } catch (_: Exception) {}
}

internal fun PaintViewModel.toggleQuickOklch(atPointer: Boolean = false) {
    if (quickOklchWindowOpen) {
        quickOklchWindowOpen = false
    } else {
        if (atPointer) {
            lastPointerScreenPosition?.let { pos ->
                quickOklchWindowX = (pos.x - 110f).coerceAtLeast(0f)
                quickOklchWindowY = (pos.y - 120f).coerceAtLeast(0f)
            }
        }
        quickOklchWindowOpen = true
    }
    persistQuickOklchState()
}

internal fun PaintViewModel.persistQuickLayerState() {
    if (!isAppContextReady()) return
    try {
        if (isLandscapeMode) {
            quickLayerWindowX_land = quickLayerWindowX
            quickLayerWindowY_land = quickLayerWindowY
        } else {
            quickLayerWindowX_port = quickLayerWindowX
            quickLayerWindowY_port = quickLayerWindowY
        }
        val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
        p.putBoolean("quick_layer_open", quickLayerWindowOpen)
        p.putFloat("quick_layer_x_land", quickLayerWindowX_land)
        p.putFloat("quick_layer_y_land", quickLayerWindowY_land)
        p.putFloat("quick_layer_x_port", quickLayerWindowX_port)
        p.putFloat("quick_layer_y_port", quickLayerWindowY_port)
        p.putFloat("quick_layer_x", quickLayerWindowX)
        p.putFloat("quick_layer_y", quickLayerWindowY)
        p.putBoolean("quick_layer_collapsed", quickLayerCollapsed)
        p.putBoolean("quick_layer_pinned", quickLayerPinned)
        p.apply()
    } catch (_: Exception) {}
}

internal fun PaintViewModel.toggleQuickLayer() {
    quickLayerWindowOpen = !quickLayerWindowOpen
    persistQuickLayerState()
}

/**
 * 处理屏幕方向（横屏 / 竖屏）切换或屏幕尺寸变动。
 * 为悬浮快捷小窗维护独立的横屏与竖屏排布记忆，杜绝旋转时将靠下/靠右的小窗单向挤压至正方形区域。
 */
internal fun PaintViewModel.handleOrientationChange(
    newIsLandscape: Boolean,
    screenWidthPx: Float,
    screenHeightPx: Float,
    density: Float,
) {
    if (screenWidthPx <= 0f || screenHeightPx <= 0f) return

    if (newIsLandscape == isLandscapeMode) {
        val clampW = if (floatingHostWidthPx > 0f) floatingHostWidthPx else screenWidthPx
        val clampH = if (floatingHostHeightPx > 0f) floatingHostHeightPx else screenHeightPx
        clampQuickWindowsToSafeArea(clampW, clampH, density)
        return
    }

    // 1. 保存当前活跃坐标到即将离去方向的独立槽位
    if (isLandscapeMode) {
        quickActionWindowX_land = quickActionWindowX
        quickActionWindowY_land = quickActionWindowY
        quickBrushWindowX_land = quickBrushWindowX
        quickBrushWindowY_land = quickBrushWindowY
        quickBrushOrientation_land = quickBrushOrientation
        quickColorWindowX_land = quickColorWindowX
        quickColorWindowY_land = quickColorWindowY
        quickOklchWindowX_land = quickOklchWindowX
        quickOklchWindowY_land = quickOklchWindowY
        quickLayerWindowX_land = quickLayerWindowX
        quickLayerWindowY_land = quickLayerWindowY
        referenceWindowX_land = referenceWindowX
        referenceWindowY_land = referenceWindowY
    } else {
        quickActionWindowX_port = quickActionWindowX
        quickActionWindowY_port = quickActionWindowY
        quickBrushWindowX_port = quickBrushWindowX
        quickBrushWindowY_port = quickBrushWindowY
        quickBrushOrientation_port = quickBrushOrientation
        quickColorWindowX_port = quickColorWindowX
        quickColorWindowY_port = quickColorWindowY
        quickOklchWindowX_port = quickOklchWindowX
        quickOklchWindowY_port = quickOklchWindowY
        quickLayerWindowX_port = quickLayerWindowX
        quickLayerWindowY_port = quickLayerWindowY
        referenceWindowX_port = referenceWindowX
        referenceWindowY_port = referenceWindowY
    }

    // 2. 切换方向标记
    isLandscapeMode = newIsLandscape

    // 3. 恢复进入新方向的独立记忆 (若新方向从未配置过，继承离去方向坐标作为初值)
    if (newIsLandscape) {
        if (isFloatingWindowPositioned(quickActionWindowX_land, quickActionWindowY_land)) {
            quickActionWindowX = quickActionWindowX_land
            quickActionWindowY = quickActionWindowY_land
        }
        if (isFloatingWindowPositioned(quickBrushWindowX_land, quickBrushWindowY_land)) {
            quickBrushWindowX = quickBrushWindowX_land
            quickBrushWindowY = quickBrushWindowY_land
        }
        quickBrushOrientation = quickBrushOrientation_land
        if (isFloatingWindowPositioned(quickColorWindowX_land, quickColorWindowY_land)) {
            quickColorWindowX = quickColorWindowX_land
            quickColorWindowY = quickColorWindowY_land
        }
        if (isFloatingWindowPositioned(quickOklchWindowX_land, quickOklchWindowY_land)) {
            quickOklchWindowX = quickOklchWindowX_land
            quickOklchWindowY = quickOklchWindowY_land
        }
        if (isFloatingWindowPositioned(quickLayerWindowX_land, quickLayerWindowY_land)) {
            quickLayerWindowX = quickLayerWindowX_land
            quickLayerWindowY = quickLayerWindowY_land
        }
        if (isFloatingWindowPositioned(referenceWindowX_land, referenceWindowY_land)) {
            referenceWindowX = referenceWindowX_land
            referenceWindowY = referenceWindowY_land
        }
    } else {
        if (isFloatingWindowPositioned(quickActionWindowX_port, quickActionWindowY_port)) {
            quickActionWindowX = quickActionWindowX_port
            quickActionWindowY = quickActionWindowY_port
        }
        if (isFloatingWindowPositioned(quickBrushWindowX_port, quickBrushWindowY_port)) {
            quickBrushWindowX = quickBrushWindowX_port
            quickBrushWindowY = quickBrushWindowY_port
        }
        quickBrushOrientation = quickBrushOrientation_port
        if (isFloatingWindowPositioned(quickColorWindowX_port, quickColorWindowY_port)) {
            quickColorWindowX = quickColorWindowX_port
            quickColorWindowY = quickColorWindowY_port
        }
        if (isFloatingWindowPositioned(quickOklchWindowX_port, quickOklchWindowY_port)) {
            quickOklchWindowX = quickOklchWindowX_port
            quickOklchWindowY = quickOklchWindowY_port
        }
        if (isFloatingWindowPositioned(quickLayerWindowX_port, quickLayerWindowY_port)) {
            quickLayerWindowX = quickLayerWindowX_port
            quickLayerWindowY = quickLayerWindowY_port
        }
        if (isFloatingWindowPositioned(referenceWindowX_port, referenceWindowY_port)) {
            referenceWindowX = referenceWindowX_port
            referenceWindowY = referenceWindowY_port
        }
    }

    // 4. 按绘画页布局夹取; 宿主尚未测量时回退到本次传入的屏幕尺寸
    val clampW = if (floatingHostWidthPx > 0f) floatingHostWidthPx else screenWidthPx
    val clampH = if (floatingHostHeightPx > 0f) floatingHostHeightPx else screenHeightPx
    clampQuickWindowsToSafeArea(clampW, clampH, density)

    // 5. 更新新方向槽位并持久化保存
    if (newIsLandscape) {
        quickActionWindowX_land = quickActionWindowX
        quickActionWindowY_land = quickActionWindowY
        quickBrushWindowX_land = quickBrushWindowX
        quickBrushWindowY_land = quickBrushWindowY
        quickColorWindowX_land = quickColorWindowX
        quickColorWindowY_land = quickColorWindowY
        quickOklchWindowX_land = quickOklchWindowX
        quickOklchWindowY_land = quickOklchWindowY
        quickLayerWindowX_land = quickLayerWindowX
        quickLayerWindowY_land = quickLayerWindowY
        referenceWindowX_land = referenceWindowX
        referenceWindowY_land = referenceWindowY
    } else {
        quickActionWindowX_port = quickActionWindowX
        quickActionWindowY_port = quickActionWindowY
        quickBrushWindowX_port = quickBrushWindowX
        quickBrushWindowY_port = quickBrushWindowY
        quickColorWindowX_port = quickColorWindowX
        quickColorWindowY_port = quickColorWindowY
        quickOklchWindowX_port = quickOklchWindowX
        quickOklchWindowY_port = quickOklchWindowY
        quickLayerWindowX_port = quickLayerWindowX
        quickLayerWindowY_port = quickLayerWindowY
        referenceWindowX_port = referenceWindowX
        referenceWindowY_port = referenceWindowY
    }
    persistQuickActionsState()
    persistQuickBrushState()
    persistQuickColorState()
    persistQuickOklchState()
    persistQuickLayerState()
}

/**
 * 夹一个窗: 有实测把手就用实测矩形, 没有时用 [fallbackLeft]/[fallbackTop]/[fallbackW]/[fallbackH]。
 *
 * 实测把手总是缺一帧 —— 宿主 Box 在 measure 阶段就回调 onSizeChanged, 而子节点的把手要到
 * place 阶段才 onGloballyPositioned, 所以首帧/方向切换时走兜底。兜底几何统一引用 DragGripSpec,
 * 与 ui 侧实际尺寸同源, 不会悄悄漂移。
 */
internal fun PaintViewModel.applyGripClamp(
    x: Float,
    y: Float,
    grip: FloatingGrip,
    fallbackLeft: Float,
    fallbackTop: Float,
    fallbackW: Float,
    fallbackH: Float,
    parentW: Float,
    parentH: Float,
    keepInside: Float,
    persist: Boolean,
    write: (Float, Float) -> Unit,
    save: () -> Unit,
) {
    val gripLeft: Float
    val gripTop: Float
    val gripW: Float
    val gripH: Float
    if (grip.placed) {
        gripLeft = grip.left
        gripTop = grip.top
        gripW = grip.width
        gripH = grip.height
    } else {
        gripLeft = fallbackLeft
        gripTop = fallbackTop
        gripW = fallbackW
        gripH = fallbackH
    }
    clampPlacedWindowOrigin(
        x, y, parentW, parentH, gripLeft, gripTop, gripW, gripH, keepInside, clampScratch,
    )
    val nx = clampScratch.x
    val ny = clampScratch.y
    if (nx != x || ny != y) {
        write(nx, ny)
        if (persist) save()
    }
}

/**
 * 将各悬浮快捷小窗按绘画页布局夹取: 左右可出界但留住 48dp 拖把手, 顶部不允许滑出。
 * 已测量的把手优先; 未测量时用各窗布局对应的把手 fallback。
 * persist=false 用于拖拽过程中的实时夹取: 只改内存坐标, 不落盘 (落盘仍由松手时的 onDragEnd 负责)。
 * 通过 [isFloatingWindowPositioned] 跳过「未定位」哨兵 (-1,-1), 避免把未打开过的窗夹到左上角,
 * 同时保留合法的左沿/左上角负坐标继续参与夹取。
 */
internal fun PaintViewModel.clampQuickWindowsToSafeArea(
    parentW: Float,
    parentH: Float,
    density: Float,
    persist: Boolean = true,
) {
    if (parentW <= 0f || parentH <= 0f) return
    val keepInside = 48f * density

    // 1. Reference Window: 顶栏整宽 (展开 38dp / 收起 28dp 拖动条)。未定位哨兵不夹。
    if (isFloatingWindowPositioned(referenceWindowX, referenceWindowY)) {
        val w = referenceWindowWidth * density
        val h = referenceWindowHeight * density
        val barH = if (referenceBarsCollapsed) {
            DragGripSpec.REFERENCE_STRIP_HEIGHT_DP
        } else {
            DragGripSpec.REFERENCE_TOP_BAR_HEIGHT_DP
        }
        applyGripClamp(
            referenceWindowX, referenceWindowY, referenceGrip,
            0f, 0f, w, minOf(barH * density, h),
            parentW, parentH, keepInside, persist,
            { nx, ny -> referenceWindowX = nx; referenceWindowY = ny },
            ::persistReferenceState,
        )
    }

    // 2. Quick Action Window: 顶栏整宽 (展开 24dp), 折叠时把手就是整窗
    if (isFloatingWindowPositioned(quickActionWindowX, quickActionWindowY)) {
        val fallbackW = when {
            quickActionCollapsed -> 64f * density
            quickActionsConfig.layoutMode == com.reverie.paint.model.QuickActionLayoutMode.COLUMN -> 56f * density
            quickActionsConfig.layoutMode == com.reverie.paint.model.QuickActionLayoutMode.ROW -> 240f * density
            quickActionsConfig.layoutMode == com.reverie.paint.model.QuickActionLayoutMode.GRID_2 -> 110f * density
            else -> 160f * density
        }
        val fallbackH = when {
            quickActionCollapsed -> 44f * density
            quickActionsConfig.layoutMode == com.reverie.paint.model.QuickActionLayoutMode.COLUMN -> 280f * density
            quickActionsConfig.layoutMode == com.reverie.paint.model.QuickActionLayoutMode.ROW -> 76f * density
            else -> 160f * density
        }
        val w = if (quickActionWindowWidth > 0f) quickActionWindowWidth else fallbackW
        val h = if (quickActionWindowHeight > 0f) quickActionWindowHeight else fallbackH
        val gripH = if (quickActionCollapsed) {
            h
        } else {
            minOf(DragGripSpec.QUICK_ACTION_TOP_BAR_HEIGHT_DP * density, h)
        }
        applyGripClamp(
            quickActionWindowX, quickActionWindowY, quickActionGrip,
            0f, 0f, w, gripH,
            parentW, parentH, keepInside, persist,
            { nx, ny -> quickActionWindowX = nx; quickActionWindowY = ny },
            ::persistQuickActionsState,
        )
    }

    // 3. Quick Brush Window: 折叠整窗; 竖向顶部握把; 横向左端握把
    if (isFloatingWindowPositioned(quickBrushWindowX, quickBrushWindowY)) {
        val fallbackW = when {
            quickBrushCollapsed -> 64f * density
            quickBrushOrientation == "vertical" -> 54f * density
            else -> (quickBrushMaxLength * 43f - 1f).coerceAtLeast(42f) * density
        }
        val fallbackH = when {
            quickBrushCollapsed -> 44f * density
            quickBrushOrientation == "vertical" -> 260f * density
            else -> 54f * density
        }
        val w = if (quickBrushWindowWidth > 0f) quickBrushWindowWidth else fallbackW
        val h = if (quickBrushWindowHeight > 0f) quickBrushWindowHeight else fallbackH
        val gripLeft: Float
        val gripTop: Float
        val gripW: Float
        val gripH: Float
        when {
            quickBrushCollapsed -> {
                gripLeft = 0f
                gripTop = 0f
                gripW = w
                gripH = h
            }
            quickBrushOrientation == "vertical" -> {
                val barW = DragGripSpec.QUICK_BRUSH_TOP_WIDTH_DP * density
                gripW = minOf(barW, w)
                gripH = minOf(DragGripSpec.QUICK_BRUSH_TOP_HEIGHT_DP * density, h)
                gripLeft = ((w - barW) / 2f).coerceAtLeast(0f)
                gripTop = 0f
            }
            else -> {
                val barH = DragGripSpec.QUICK_BRUSH_SIDE_HEIGHT_DP * density
                gripW = minOf(DragGripSpec.QUICK_BRUSH_SIDE_WIDTH_DP * density, w)
                gripH = minOf(barH, h)
                gripLeft = DragGripSpec.QUICK_BRUSH_SIDE_INSET_DP * density
                gripTop = ((h - barH) / 2f).coerceAtLeast(0f)
            }
        }
        applyGripClamp(
            quickBrushWindowX, quickBrushWindowY, quickBrushGrip,
            gripLeft, gripTop, gripW, gripH,
            parentW, parentH, keepInside, persist,
            { nx, ny -> quickBrushWindowX = nx; quickBrushWindowY = ny },
            ::persistQuickBrushState,
        )
    }

    // 4. Quick Color Window: 折叠整窗 46dp; 展开居中 60x16 小横条
    if (isFloatingWindowPositioned(quickColorWindowX, quickColorWindowY)) {
        val fallbackW = if (quickColorCollapsed) 46f * density else 212f * density
        val fallbackH = if (quickColorCollapsed) 46f * density else 272f * density
        val w = if (quickColorWindowWidth > 0f) quickColorWindowWidth else fallbackW
        val h = if (quickColorWindowHeight > 0f) quickColorWindowHeight else fallbackH
        val gripLeft: Float
        val gripW: Float
        val gripH: Float
        if (quickColorCollapsed) {
            gripLeft = 0f
            gripW = w
            gripH = h
        } else {
            val barW = DragGripSpec.PILL_WIDTH_DP * density
            gripW = minOf(barW, w)
            gripH = minOf(DragGripSpec.PILL_HEIGHT_DP * density, h)
            gripLeft = ((w - barW) / 2f).coerceAtLeast(0f)
        }
        applyGripClamp(
            quickColorWindowX, quickColorWindowY, quickColorGrip,
            gripLeft, 0f, gripW, gripH,
            parentW, parentH, keepInside, persist,
            { nx, ny -> quickColorWindowX = nx; quickColorWindowY = ny },
            ::persistQuickColorState,
        )
    }

    // 5. Quick OKLCH Window: 折叠整窗 46dp; 展开宽 256dp, 把手同颜色窗
    if (isFloatingWindowPositioned(quickOklchWindowX, quickOklchWindowY)) {
        val fallbackW = if (quickOklchCollapsed) 46f * density else 256f * density
        val fallbackH = if (quickOklchCollapsed) 46f * density else 320f * density
        val w = if (quickOklchWindowWidth > 0f) quickOklchWindowWidth else fallbackW
        val h = if (quickOklchWindowHeight > 0f) quickOklchWindowHeight else fallbackH
        val gripLeft: Float
        val gripW: Float
        val gripH: Float
        if (quickOklchCollapsed) {
            gripLeft = 0f
            gripW = w
            gripH = h
        } else {
            val barW = DragGripSpec.PILL_WIDTH_DP * density
            gripW = minOf(barW, w)
            gripH = minOf(DragGripSpec.PILL_HEIGHT_DP * density, h)
            gripLeft = ((w - barW) / 2f).coerceAtLeast(0f)
        }
        applyGripClamp(
            quickOklchWindowX, quickOklchWindowY, quickOklchGrip,
            gripLeft, 0f, gripW, gripH,
            parentW, parentH, keepInside, persist,
            { nx, ny -> quickOklchWindowX = nx; quickOklchWindowY = ny },
            ::persistQuickOklchState,
        )
    }

    // 6. Quick Layer Window: 折叠整窗 46dp; 展开顶栏 38dp
    if (isFloatingWindowPositioned(quickLayerWindowX, quickLayerWindowY)) {
        val fallbackW = if (quickLayerCollapsed) 46f * density else 156f * density
        val fallbackH = if (quickLayerCollapsed) 46f * density else 290f * density
        val w = if (quickLayerWindowWidth > 0f) quickLayerWindowWidth else fallbackW
        val h = if (quickLayerWindowHeight > 0f) quickLayerWindowHeight else fallbackH
        val gripH = if (quickLayerCollapsed) {
            h
        } else {
            minOf(DragGripSpec.QUICK_LAYER_TOP_BAR_HEIGHT_DP * density, h)
        }
        applyGripClamp(
            quickLayerWindowX, quickLayerWindowY, quickLayerGrip,
            0f, 0f, w, gripH,
            parentW, parentH, keepInside, persist,
            { nx, ny -> quickLayerWindowX = nx; quickLayerWindowY = ny },
            ::persistQuickLayerState,
        )
    }
}

internal fun PaintViewModel.getOrderedFavoriteBrushes(): List<BrushPresetInfo> {
    val favs = brushPresets.filter { isFavoriteBrush(it.id) }
    if (quickBrushOrder.isEmpty()) return favs
    val orderMap = quickBrushOrder.withIndex().associate { it.value to it.index }
    return favs.sortedWith(compareBy({ orderMap[it.id] ?: Int.MAX_VALUE }, { it.name }))
}

internal fun PaintViewModel.executeQuickAction(action: com.reverie.paint.model.QuickAction) {
    when (action) {
        com.reverie.paint.model.QuickAction.LOCK_VIEW -> {
            isViewTransformLocked = !isViewTransformLocked
        }
        com.reverie.paint.model.QuickAction.FLIP_H -> {
            // 快捷面板固定为"视图翻转": 这里要的是随时可切回的临时镜像,
            // 完全翻转要逐层改像素且进撤销栈, 放快捷面板上容易误触。
            toggleViewFlipHorizontal()
        }
        com.reverie.paint.model.QuickAction.FLIP_V -> {
            toggleViewFlipVertical()
        }
        com.reverie.paint.model.QuickAction.RESET_VIEW -> {
            requestUiCommand("reset_view")
        }
        com.reverie.paint.model.QuickAction.ZOOM_IN -> requestUiCommand(CanvasViewportStep.ZOOM_IN.command)
        com.reverie.paint.model.QuickAction.ZOOM_OUT -> requestUiCommand(CanvasViewportStep.ZOOM_OUT.command)
        com.reverie.paint.model.QuickAction.ROTATE_CCW -> requestUiCommand(CanvasViewportStep.ROTATE_CCW.command)
        com.reverie.paint.model.QuickAction.ROTATE_CW -> requestUiCommand(CanvasViewportStep.ROTATE_CW.command)
        com.reverie.paint.model.QuickAction.UNDO -> {
            undoFromPanel()
        }
        com.reverie.paint.model.QuickAction.REDO -> {
            redoFromPanel()
        }
        com.reverie.paint.model.QuickAction.TOGGLE_ERASER -> {
            if (currentToolId == "eraser") {
                applyTool("brush")
            } else {
                applyTool("eraser")
            }
        }
        com.reverie.paint.model.QuickAction.ERASER_MODE -> {
            toggleEraserMode()
        }
        com.reverie.paint.model.QuickAction.LAST_TOOL -> {
            val t = lastToolId
            if (t.isNotEmpty() && t != currentToolId) {
                applyTool(t)
            }
        }
        com.reverie.paint.model.QuickAction.PICK_COLOR -> {
            applyTool("picker")
        }
        com.reverie.paint.model.QuickAction.SWAP_COLOR -> {
            swapColors()
        }
        com.reverie.paint.model.QuickAction.BRUSH_SIZE_INC -> {
            val newSize = (brushSize * 1.25).coerceAtMost(effectiveBrushMaxSize)
            updateBrushSize(newSize)
        }
        com.reverie.paint.model.QuickAction.BRUSH_SIZE_DEC -> {
            val minL = brushMinSizeLimit.coerceAtLeast(0.5)
            val newSize = (brushSize / 1.25).coerceAtLeast(minL)
            updateBrushSize(newSize)
        }
        com.reverie.paint.model.QuickAction.ALPHA_LOCK -> {
            val idx = currentLayerIndex
            val layer = layers.firstOrNull { it.index == idx }
            if (layer != null && !layer.isBackground && layer.nodeType != 3) {
                val curr = layer.alphaLocked
                setLayerAlphaLocked(idx, !curr)
            }
        }
        com.reverie.paint.model.QuickAction.NEW_LAYER -> {
            addLayer()
        }
        com.reverie.paint.model.QuickAction.DUPLICATE_LAYER -> {
            copyLayer(currentLayerIndex)
        }
        com.reverie.paint.model.QuickAction.MERGE_DOWN -> {
            mergeDown(currentLayerIndex)
        }
        com.reverie.paint.model.QuickAction.CLEAR_LAYER -> {
            clearLayer(currentLayerIndex)
        }
        com.reverie.paint.model.QuickAction.DISABLE_TOUCH -> {
            isCanvasTouchDisabled = !isCanvasTouchDisabled
        }
        com.reverie.paint.model.QuickAction.TOGGLE_QUICK_COLOR -> {
            toggleQuickColor(atPointer = true)
        }
        com.reverie.paint.model.QuickAction.TOGGLE_QUICK_OKLCH -> {
            toggleQuickOklch(atPointer = true)
        }
        com.reverie.paint.model.QuickAction.TOGGLE_QUICK_LAYER -> {
            toggleQuickLayer()
        }
        com.reverie.paint.model.QuickAction.SHADING_ASSIST -> {
            openShadingAssist()
        }
        com.reverie.paint.model.QuickAction.PAN -> {
            if (currentToolId == "pan") {
                applyTool(if (lastToolId.isNotEmpty() && lastToolId != "pan") lastToolId else "brush")
            } else {
                applyTool("pan")
            }
        }
    }
}
