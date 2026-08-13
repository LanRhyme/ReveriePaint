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
 * Keeps the classic category + list layout; the list rows now show real
 * Krita preset thumbnails (.kpp files ARE PNG images with the preset XML
 * embedded) and tapping one activates it in the brush engine.
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .noRippleClickable(onClose), // Click outside to close
    ) {
        Box(
            modifier = Modifier
                .padding(start = 48.dp) // Offset from ToolRail
                .align(Alignment.CenterStart)
                .width(320.dp)
                .fillMaxHeight(0.75f)
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
                            .width(88.dp)
                            .fillMaxHeight()
                            .background(Morandi.panel.copy(alpha = opacity))
                    ) {
                        items(categories) { cat ->
                            val isSelected = cat == selectedCategory
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
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

                    // Right Brushes (real Krita presets)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        // Top Bar
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

                        // Brushes List
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
                                        .clickable { vm.selectBrushPreset(preset.index) }
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
                                            Text("使用中", color = Morandi.accent, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Brush parameter sliders (size / opacity / flow) applied to
                // the active Krita preset's settings in C++
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morandi.panel.copy(alpha = opacity))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    BrushParamSlider("大小", vm.brushSize, 1.0, 200.0) { vm.updateBrushSize(it) }
                    BrushParamSlider("不透明度", vm.brushOpacity, 0.05, 1.0) { vm.updateBrushOpacity(it) }
                    BrushParamSlider("流量", vm.brushFlow, 0.05, 1.0) { vm.updateBrushFlow(it) }
                }
            }
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
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 11.sp,
            modifier = Modifier.width(50.dp),
        )
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
