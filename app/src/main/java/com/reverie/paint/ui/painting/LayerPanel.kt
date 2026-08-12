package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi

/**
 * Layer panel (dropdown at top-right, 画世界 Pro style):
 * layer list with visibility toggle + select, add / delete actions.
 */
@Composable
fun LayerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Native getters are not Compose state by themselves. Reading the
    // revision here makes add/remove/select/visibility changes recompose the
    // complete layer list immediately.
    val layerRevision = vm.layerRevision

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { onClose() },
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 8.dp)
                    .width(270.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                    .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图层", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${vm.layerCount} 层", color = Morandi.subText, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                IconBtn("✕", "关闭", onClose)
            }

            Spacer(Modifier.height(8.dp))

            // Layer list - top layer first (画世界 style)
            Column(
                modifier =
                    Modifier
                        .height(280.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (i in 0 until vm.layerCount) {
                    val idx = vm.layerCount - 1 - i
                    val selected = idx == vm.currentLayerIndex
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) Morandi.accent else Morandi.panel)
                                .clickable {
                                    vm.setCurrentLayer(idx)
                                    onClose()
                                }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (vm.layerVisible(idx)) "👁" else "◌",
                            color = if (selected) Color.White else Morandi.subText,
                            fontSize = 14.sp,
                            modifier =
                                Modifier
                                    .width(28.dp)
                                    .clickable { vm.toggleLayerVisible(idx) },
                        )
                        Text(
                            vm.layerName(idx),
                            color = if (selected) Color.White else Morandi.text,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (selected) "当前" else "",
                            color = if (selected) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Blend mode (current layer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("混合", color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                Spacer(Modifier.weight(1f))
                // Simple horizontal scrollable list of blend modes
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(30.dp)
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((opId, label) in vm.blendModes) {
                        val selected =
                            com.reverie.paint.core.ReverieCoreBridge
                                .layerBlendMode(vm.currentLayerIndex) == opId
                        Box(
                            modifier =
                                Modifier
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (selected) Morandi.accent else Morandi.panel)
                                    .clickable { vm.setLayerBlendMode(vm.currentLayerIndex, opId) }
                                    .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.White else Morandi.text,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelAction("＋ 新建图层", { vm.addLayer() }, Modifier.weight(1f))
                PanelAction("－ 删除", { vm.removeLayer() }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun PanelAction(
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Morandi.text, fontSize = 12.sp)
    }
}
