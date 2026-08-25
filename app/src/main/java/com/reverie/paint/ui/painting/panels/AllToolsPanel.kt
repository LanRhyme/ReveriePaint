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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
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
                .padding(start = 52.dp, top = 48.dp, bottom = 48.dp)
                .align(Alignment.CenterStart)
                .noRippleClickable { /* consume clicks inside panel */ }
                .width(200.dp)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.barStyle(opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .border(1.dp, Morandi.border.copy(alpha = opacity), panelShape)
                .padding(12.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "全部工具",
                        color = Morandi.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_sliders),
                        contentDescription = "自定义工具栏",
                        tint = Morandi.icon,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showCustomizeDialog = true }
                            .padding(4.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groupedTools.forEach { (group, tools) ->
                        item(key = group.name) {
                            Text(
                                group.label,
                                color = Morandi.subText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                            )
                        }
                        val chunked = tools.chunked(3)
                        items(chunked) { rowTools ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                rowTools.forEach { t ->
                                    val isSelected = if (t == Tool.REFERENCE) vm.referenceWindowOpen else tool == t
                                    val cellSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .pressScale(cellSource, pressedScale = 0.94f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) Morandi.accent.copy(alpha = 0.18f)
                                                else Color.Transparent
                                            )
                                            .liquidHighlight(cellSource, Color.White, radius = 28.dp)
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
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(toolIcon(t)),
                                            contentDescription = t.label,
                                            tint = if (isSelected) Morandi.accentHi else Morandi.icon,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            t.label,
                                            color = if (isSelected) Morandi.accentHi else Morandi.text,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                repeat(3 - rowTools.size) { Spacer(Modifier.weight(1f)) }
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
