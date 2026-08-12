package com.reverie.paint.ui.painting

import com.reverie.paint.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi

/**
 * Layer panel (dropdown at top-right, 画世界 Pro style)
 */
@Composable
fun LayerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
) {
    val layerRevision = vm.layerRevision

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = 8.dp)
                .width(270.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = opacity))
                .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
        ) {
            // Top Actions Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(Icons.Default.Add, onClick = { vm.addLayer() })
                IconAction(Icons.Default.Folder, onClick = {})
                IconAction(Icons.Default.Lock, onClick = {})
                IconAction(Icons.Default.CallMerge, onClick = {})
                IconAction(Icons.Default.GridView, onClick = {})
                IconAction(Icons.Default.CenterFocusWeak, onClick = {})
            }
            
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            // Layer list - top layer first
            Column(
                modifier = Modifier
                    .height(280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (i in 0 until vm.layerCount) {
                    val idx = vm.layerCount - 1 - i
                    val selected = idx == vm.currentLayerIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(if (selected) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { vm.setCurrentLayer(idx) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Visibility toggle icon
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .noRippleClickable { vm.toggleLayerVisible(idx) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (vm.layerVisible(idx)) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Visibility",
                                tint = if (vm.layerVisible(idx)) (if (selected) Morandi.accent else Morandi.icon) else Morandi.subText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        // Thumbnail Placeholder
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                                .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                        )
                        
                        Text(
                            text = vm.layerName(idx),
                            color = if (selected) Morandi.accent else Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (i < vm.layerCount - 1) {
                        Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
                    }
                }
            }
            
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            // Blend mode & Opacity (current layer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("正常", color = Morandi.text, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("不透明度", color = Morandi.subText, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text("100%", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Morandi.icon, modifier = Modifier.size(18.dp))
    }
}
