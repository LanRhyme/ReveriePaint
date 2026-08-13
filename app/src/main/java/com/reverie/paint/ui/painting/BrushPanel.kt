package com.reverie.paint.ui.painting

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.components.noRippleClickable

/**
 * Brush library panel with Krita's real bundled presets (.kpp).
 * Main view = category rail + preset list. Tapping the already-selected
 * preset opens the second-level property page (size/opacity/flow).
 */
@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
) {
    val categories = listOf("全部", "基础", "圆头", "勾线", "水彩", "喷枪", "纹理", "橡皮")
    var selectedCategory by remember { mutableStateOf("全部") }
    // null = list view; non-null = second-level property page for that preset
    var detailIndex by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .noRippleClickable(onClose),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 48.dp)
                .align(Alignment.CenterStart)
                .width(320.dp)
                .fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi.copy(alpha = opacity))
                .border(1.dp, Morandi.border.copy(alpha = opacity), RoundedCornerShape(12.dp))
                .clickable(enabled = false) {}
        ) {
            if (detailIndex != null && detailIndex!! < vm.brushPresets.size) {
                BrushPropertyPage(
                    vm = vm,
                    presetIndex = detailIndex!!,
                    onBack = { detailIndex = null },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Left categories
                        LazyColumn(
                            modifier = Modifier
                                .width(88.dp)
                                .fillMaxHeight()
                                .background(Morandi.panel.copy(alpha = opacity))
                        ) {
                            items(categories) { cat ->
                                val sel = cat == selectedCategory
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .background(if (sel) Morandi.panelHi.copy(alpha = opacity) else Color.Transparent)
                                        .clickable { selectedCategory = cat },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = cat,
                                        color = if (sel) Morandi.accent else Morandi.subText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }

                        // Right preset list
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("笔刷库", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${vm.brushPresets.size} 个 Krita 预设",
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(vm.brushPresets) { preset ->
                                    val isSelected = preset.index == vm.brushPresetIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Morandi.accent.copy(alpha = 0.15f) else Morandi.panel.copy(alpha = 0.5f))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.dp,
                                                color = if (isSelected) Morandi.accent else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                // Tap selects; tap the selected one again -> property page
                                                if (isSelected) detailIndex = preset.index
                                                else vm.selectBrushPreset(preset.index)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val bmp = rememberBytes(preset.thumbBytes)
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = preset.name,
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Morandi.panelHi)
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                preset.name,
                                                color = if (isSelected) Morandi.accent else Morandi.subText,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (isSelected) {
                                                Text("使用中 · 点按调属性", color = Morandi.accent, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom toolbar (kept from the original panel design)
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
}

/** Second-level page: brush property sliders for the active preset. */
@Composable
private fun BrushPropertyPage(
    vm: PaintViewModel,
    presetIndex: Int,
    onBack: () -> Unit,
) {
    val preset = vm.brushPresets.getOrNull(presetIndex)
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = Morandi.text,
                modifier = Modifier.size(22.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                preset?.name ?: "",
                color = Morandi.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("笔刷属性", color = Morandi.subText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            BrushParamSlider("大小", vm.brushSize, 1.0, 200.0) { vm.updateBrushSize(it) }
            BrushParamSlider("不透明度", vm.brushOpacity, 0.05, 1.0) { vm.updateBrushOpacity(it) }
            BrushParamSlider("流量", vm.brushFlow, 0.05, 1.0) { vm.updateBrushFlow(it) }
        }
    }
}

@Composable
private fun rememberBytes(bytes: ByteArray): android.graphics.Bitmap? {
    androidx.compose.runtime.remember(bytes) { }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

@Composable
private fun BrushParamSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (max > 100) "${value.toInt()}" else "${(value * 100).toInt()}%",
            color = Morandi.text,
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
        )
    }
}
