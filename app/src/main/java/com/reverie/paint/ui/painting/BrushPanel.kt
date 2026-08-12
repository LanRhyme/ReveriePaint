package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.BrushPresets
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable

@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
) {
    val categories = listOf("最近使用", "艺术画笔", "绘画肌理", "漫画网点", "光效", "水彩", "水墨", "油画", "3D画笔", "常规画笔", "圆头画笔", "勾线画笔", "喷枪", "材质元素")
    var selectedCategory by remember { mutableStateOf("圆头画笔") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .noRippleClickable(onClose) // Click outside to close
    ) {
        Box(
            modifier = Modifier
                .padding(start = 48.dp) // Offset from ToolRail
                .align(Alignment.CenterStart)
                .width(300.dp)
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi.copy(alpha = opacity))
                .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(12.dp))
                .clickable(enabled = false) {} // Prevent click-through
        ) {
            Column(Modifier.fillMaxSize()) {
                // Main content: Left categories, Right brushes
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Left Categories
                    LazyColumn(
                        modifier = Modifier
                            .width(90.dp)
                            .fillMaxHeight()
                            .background(Morandi.panel.copy(alpha = opacity))
                    ) {
                        items(categories) { cat ->
                            val isSelected = cat == selectedCategory
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(if (isSelected) Morandi.panelHi.copy(alpha = opacity) else Color.Transparent)
                                    .clickable { selectedCategory = cat },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = cat,
                                    color = if (isSelected) Morandi.accent else Morandi.subText,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }

                    // Right Brushes
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        // Top Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("笔刷库", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                            Spacer(Modifier.width(16.dp))
                            Icon(Icons.Default.Tune, contentDescription = "Settings", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                        }

                        // Brushes List
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(BrushPresets) { preset ->
                                val isSelected = vm.brushSize == preset.size
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Morandi.accent.copy(alpha = 0.15f) else Morandi.panel.copy(alpha = 0.5f))
                                        .border(
                                            width = if (isSelected) 1.5.dp else 0.dp,
                                            color = if (isSelected) Morandi.accent else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { vm.updateBrushSize(preset.size) }
                                        .padding(8.dp)
                                ) {
                                    // Simulated Brush Stroke Image
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.8f)
                                                .height(if (preset.size > 20) 16.dp else 8.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (isSelected) Color(0xFFE2E2E2) else Color(0xFFAAAAAA))
                                        )
                                    }
                                    
                                    // Text Overlay
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(preset.name, color = if (isSelected) Morandi.accent else Morandi.subText, fontSize = 12.sp)
                                        Text("${preset.size.toInt()}", color = Morandi.subText, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Morandi.panel.copy(alpha = opacity))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.Folder, contentDescription = "Folder", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Morandi.icon, modifier = Modifier.size(20.dp).clickable {})
                }
            }
        }
    }
}
