/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.model.GuideMode
import com.reverie.paint.ui.components.noRippleClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import com.reverie.paint.ui.painting.ToolbarCustomizeDialog

private sealed interface AllToolItem {
    data class Regular(val tool: Tool) : AllToolItem
    object GuideLines : AllToolItem
}

@Composable
fun AllToolsPanel(
    vm: PaintViewModel,
    tool: Tool,
    onTool: (Tool) -> Unit,
    onOpenBrush: () -> Unit,
    onClose: () -> Unit,
    opacity: Float = 0.94f,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val groupedTools = remember(vm.pinnedTools) {
        val customSet = vm.pinnedTools.toSet()
        val moreTools = Tool.entries.filter { it !in customSet && it != Tool.SYMMETRY && it != Tool.PERSPECTIVE }
        ToolGroup.entries.map { g -> g to moreTools.filter { it.group == g } }
            .filter { it.second.isNotEmpty() }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val panelShape = RoundedCornerShape(14.dp)

    DisposableEffect(Unit) {
        onDispose {
            vm.allToolsPanelMeasuredBounds = null
            vm.viewToolsMeasuredBounds = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .systemHoverIcon(context)
            .noRippleClickable(onClose),
    ) {
        val (baseAlign, basePaddingModifier) = if (vm.floatingUiConfig.enabled) {
            val hostPos = vm.floatingUiConfig.toolRailPosition
            val mDp = vm.floatingUiConfig.popupInwardMarginDp(hostPos).dp
            when (hostPos.edge) {
                com.reverie.paint.model.PanelEdge.LEFT -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.TopStart to Modifier.padding(start = mDp, top = 24.dp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.CenterStart to Modifier.padding(start = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomStart to Modifier.padding(start = mDp, bottom = 24.dp)
                    }
                }
                com.reverie.paint.model.PanelEdge.RIGHT -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.TopEnd to Modifier.padding(end = mDp, top = 24.dp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.CenterEnd to Modifier.padding(end = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomEnd to Modifier.padding(end = mDp, bottom = 24.dp)
                    }
                }
                com.reverie.paint.model.PanelEdge.TOP -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.TopStart to Modifier.padding(start = 24.dp, top = mDp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.TopCenter to Modifier.padding(top = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.TopEnd to Modifier.padding(end = 24.dp, top = mDp)
                    }
                }
                com.reverie.paint.model.PanelEdge.BOTTOM -> {
                    when (hostPos.alignment) {
                        com.reverie.paint.model.PanelAlignment.START -> Alignment.BottomStart to Modifier.padding(start = 24.dp, bottom = mDp)
                        com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.BottomCenter to Modifier.padding(bottom = mDp)
                        com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomEnd to Modifier.padding(end = 24.dp, bottom = mDp)
                    }
                }
            }
        } else {
            (if (vm.leftHandMode) Alignment.CenterEnd else Alignment.CenterStart) to Modifier.padding(
                start = if (vm.leftHandMode) 0.dp else 52.dp,
                end = if (vm.leftHandMode) 52.dp else 0.dp,
                top = 48.dp,
                bottom = 48.dp,
            )
        }

        Box(
            modifier = Modifier
                .systemHoverIcon(context)
                .align(baseAlign)
                .then(basePaddingModifier)
                .noRippleClickable { /* consume clicks inside panel */ }
                .width(236.dp)
                .clip(panelShape)
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        vm.allToolsPanelMeasuredBounds = coords.boundsInRoot()
                    }
                }
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.barStyle(if (opacity >= 0.99f) 0.92f else opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .glassBorder(panelShape)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(0.82f)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp, start = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.tool_all_tools_title),
                        color = Morandi.text,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_sliders),
                        contentDescription = stringResource(R.string.tool_customize_bar),
                        tint = Morandi.icon,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showCustomizeDialog = true }
                            .padding(3.dp)
                    )
                }

                val listState = rememberLazyListState()
                LaunchedEffect(vm.onboardingActive, vm.onboardingStep) {
                    if (vm.onboardingActive && vm.onboardingStep == 2) {
                        val viewGroupIndex = groupedTools.indexOfFirst { it.first == ToolGroup.VIEW }
                        if (viewGroupIndex != -1) {
                            var itemIndex = 0
                            for (i in 0 until viewGroupIndex) {
                                itemIndex += 1 // header
                                val count = groupedTools[i].second.size
                                itemIndex += (count + 1) / 2 // chunked rows
                            }
                            listState.animateScrollToItem(itemIndex)
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groupedTools.forEach { (group, tools) ->
                        val isViewGroup = group == ToolGroup.VIEW
                        val itemsList = buildList {
                            for (t in tools) {
                                add(AllToolItem.Regular(t))
                                if (t == Tool.DRAWING_GUIDE) {
                                    add(AllToolItem.GuideLines)
                                }
                            }
                        }
                        val chunked = itemsList.chunked(2)

                        if (isViewGroup) {
                            item(key = group.name) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { coords ->
                                            if (coords.isAttached) {
                                                vm.viewToolsMeasuredBounds = coords.boundsInRoot()
                                            }
                                        },
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        group.displayName,
                                        color = Morandi.subText,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                                    )
                                    chunked.forEach { rowTools ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowTools.forEach { item ->
                                                AllToolCell(
                                                    item = item,
                                                    tool = tool,
                                                    vm = vm,
                                                    onTool = onTool,
                                                    onOpenBrush = onOpenBrush,
                                                    onClose = onClose,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            if (rowTools.size == 1) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            item(key = group.name) {
                                Text(
                                    group.displayName,
                                    color = Morandi.subText,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                                )
                            }
                            items(chunked) { rowTools ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowTools.forEach { item ->
                                        AllToolCell(
                                            item = item,
                                            tool = tool,
                                            vm = vm,
                                            onTool = onTool,
                                            onOpenBrush = onOpenBrush,
                                            onClose = onClose,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (rowTools.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomizeDialog) {
        ToolbarCustomizeDialog(
            vm = vm,
            onClose = { showCustomizeDialog = false }
        )
    }
}

@Composable
private fun AllToolCell(
    item: AllToolItem,
    tool: Tool,
    vm: PaintViewModel,
    onTool: (Tool) -> Unit,
    onOpenBrush: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is AllToolItem.Regular -> {
            val t = item.tool
            val isSelected = when (t) {
                Tool.REFERENCE -> vm.referenceWindowOpen
                Tool.SHORTCUT -> vm.quickActionWindowOpen
                Tool.QUICK_BRUSH -> vm.quickBrushWindowOpen
                Tool.QUICK_COLOR -> vm.quickColorWindowOpen
                Tool.QUICK_OKLCH -> vm.quickOklchWindowOpen
                Tool.QUICK_LAYER -> vm.quickLayerWindowOpen
                Tool.DRAWING_GUIDE -> vm.drawingGuide.mode != GuideMode.OFF
                Tool.SYMMETRY -> vm.drawingGuide.mode == GuideMode.SYMMETRY
                Tool.PERSPECTIVE -> vm.drawingGuide.mode == GuideMode.PERSPECTIVE
                else -> tool == t
            }
            val cellSource = remember { MutableInteractionSource() }
            val toolLabel = t.annotatedName(vm)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier
                    .height(34.dp)
                    .pressScale(cellSource, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isSelected) Morandi.accent.copy(alpha = 0.22f)
                        else Morandi.panelHi.copy(alpha = 0.35f)
                    )
                    .then(
                        if (isSelected) Modifier.border(0.8.dp, Morandi.accent.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                        else Modifier
                    )
                    .liquidHighlight(cellSource, Color.White, radius = 24.dp)
                    .clickable(interactionSource = cellSource, indication = null) {
                        if (t == tool && t.group == ToolGroup.BRUSH) {
                            vm.updateBrushPanelCategory(
                                when (t) {
                                    Tool.ERASER -> "橡皮擦"
                                    Tool.SMUDGE -> "混合"
                                    else -> vm.brushPanelSelectedCategory
                                }
                            )
                            onOpenBrush()
                            onClose()
                        } else {
                            onTool(t)
                            onClose()
                        }
                    }
                    .padding(horizontal = 7.dp)
            ) {
                Icon(
                    painter = painterResource(toolIcon(t)),
                    contentDescription = toolLabel,
                    tint = if (isSelected) Morandi.accentHi else Morandi.icon,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = toolLabel,
                    color = if (isSelected) Morandi.accentHi else Morandi.text,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AllToolItem.GuideLines -> {
            val isSelected = vm.drawingGuideLinesVisible
            val cellSource = remember { MutableInteractionSource() }
            val toolLabel = stringResource(R.string.tool_guide_lines)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier
                    .height(34.dp)
                    .pressScale(cellSource, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isSelected) Morandi.accent.copy(alpha = 0.22f)
                        else Morandi.panelHi.copy(alpha = 0.35f)
                    )
                    .then(
                        if (isSelected) Modifier.border(0.8.dp, Morandi.accent.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                        else Modifier
                    )
                    .liquidHighlight(cellSource, Color.White, radius = 24.dp)
                    .clickable(interactionSource = cellSource, indication = null) {
                        vm.toggleDrawingGuideLines()
                    }
                    .padding(horizontal = 7.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_guide_lines),
                    contentDescription = toolLabel,
                    tint = if (isSelected) Morandi.accentHi else Morandi.icon,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = toolLabel,
                    color = if (isSelected) Morandi.accentHi else Morandi.text,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
