package com.reverie.paint.ui.painting

import com.reverie.paint.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.components.ReIconButton

/**
 * Layer panel (dropdown at top-right, 画世界 Pro style)
 */
@Composable
fun LayerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layerRevision = vm.layerRevision

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 8.dp)
                    .width(280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}, // Consume clicks
        ) {
            // Top Actions Toolbar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
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

            // Layer list - top layer first (画世界 style)
            Column(
                modifier =
                    Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                for (i in 0 until vm.layerCount) {
                    val idx = vm.layerCount - 1 - i
                    val selected = idx == vm.currentLayerIndex
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(if (selected) Morandi.accentHi else Morandi.panelHi)
                                .clickable { vm.setCurrentLayer(idx) }
                                .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (vm.layerVisible(idx)) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Visibility",
                            tint = if (selected) Color.White else Morandi.icon,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { vm.toggleLayerVisible(idx) }
                        )
                        
                        // Thumbnail Placeholder
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White) // Mock thumbnail
                                .border(1.dp, Morandi.border, RoundedCornerShape(4.dp))
                        )
                        
                        Spacer(Modifier.width(12.dp))
                        
                        Text(
                            vm.layerName(idx),
                            color = if (selected) Color.White else Morandi.text,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (i == 0) { // Just to mock the UI state
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = null,
                                tint = if (selected) Color.White else Morandi.icon,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (i < vm.layerCount - 1) {
                        Box(Modifier.fillMaxWidth().padding(start = 44.dp).height(1.dp).background(Morandi.border))
                    }
                }
            }
            
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            // Blend mode (current layer)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("正常", color = Morandi.text, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("不透明度", color = Morandi.subText, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text("100%", color = Morandi.text, fontSize = 12.sp)
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
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Morandi.text, modifier = Modifier.size(20.dp))
    }
}
