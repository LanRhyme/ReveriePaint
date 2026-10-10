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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        val moreTools = Tool.entries.filter { it !in customSet }
        ToolGroup.entries.map { g -> g to moreTools.filter { it.group == g } }
            .filter { it.second.isNotEmpty() }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val panelShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .systemHoverIcon(context)
            .noRippleClickable(onClose),
    ) {
        Box(
            modifier = Modifier
                .systemHoverIcon(context)
                .padding(
                    start = if (vm.leftHandMode) 0.dp else 52.dp,
                    end = if (vm.leftHandMode) 52.dp else 0.dp,
                    top = 48.dp,
                    bottom = 48.dp,
                )
                .align(if (vm.leftHandMode) Alignment.CenterEnd else Alignment.CenterStart)
                .noRippleClickable { /* consume clicks inside panel */ }
                .width(236.dp)
                .shadow(16.dp, panelShape, spotColor = Color.Black.copy(alpha = 0.5f))
                .clip(panelShape)
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

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groupedTools.forEach { (group, tools) ->
                        item(key = group.name) {
                            Text(
                                group.displayName,
                                color = Morandi.subText,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }

                        val chunked = tools.chunked(2)
                        items(chunked) { rowTools ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowTools.forEach { t ->
                                    val isSelected = when (t) {
                                        Tool.REFERENCE -> vm.referenceWindowOpen
                                        Tool.SHORTCUT -> vm.quickActionWindowOpen
                                        Tool.QUICK_BRUSH -> vm.quickBrushWindowOpen
                                        Tool.QUICK_COLOR -> vm.quickColorWindowOpen
                                        Tool.QUICK_LAYER -> vm.quickLayerWindowOpen
                                        Tool.SYMMETRY -> vm.drawingGuide.mode == GuideMode.SYMMETRY
                                        Tool.PERSPECTIVE -> vm.drawingGuide.mode == GuideMode.PERSPECTIVE
                                        else -> tool == t
                                    }
                                    val cellSource = remember { MutableInteractionSource() }
                                    val toolLabel = stringResource(t.labelRes())

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .weight(1f)
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
                                if (rowTools.size == 1) {
                                    Spacer(Modifier.weight(1f))
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
}
