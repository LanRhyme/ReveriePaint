package com.reverie.paint.ui.painting

import com.reverie.paint.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.BrushPresets
import com.reverie.paint.ui.components.RePanel
import com.reverie.paint.ui.theme.Morandi

/**
 * Brush settings panel (bottom sheet, 画世界 Pro style):
 * preset brushes, size slider, opacity slider, softness slider.
 */
@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RePanel(title = "笔刷", onClose = onClose, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))

            // Presets
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Simpler: grid of presets, 3 per row
                BrushPresets.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (p in row) {
                            BrushPresetCard(
                                name = p.name,
                                selected = vm.brushSize == p.size,
                                onTap = { vm.updateBrushSize(p.size) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Size slider
            LabeledSlider(
                label = "大小",
                valueText = "${vm.brushSize.toInt()}",
                value = ((vm.brushSize - 1) / 99.0).toFloat(),
                onValue = { vm.updateBrushSize(1.0 + it * 99.0) },
            )
            Spacer(Modifier.height(8.dp))

            // Opacity slider
            LabeledSlider(
                label = "不透明",
                valueText = "${(vm.brushOpacity * 100).toInt()}%",
                value = vm.brushOpacity.toFloat(),
                onValue = { vm.updateBrushOpacity(it.toDouble()) },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BrushPresetCard(
    name: String,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Morandi.accent else Morandi.panel)
                .border(1.dp, if (selected) Morandi.accentHi else Morandi.border, RoundedCornerShape(10.dp))
                .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = if (selected) Morandi.onAccent else Morandi.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Slider(
            value = value,
            onValueChange = onValue,
            colors =
                SliderDefaults.colors(
                    thumbColor = Morandi.accentHi,
                    activeTrackColor = Morandi.accent,
                    inactiveTrackColor = Morandi.border,
                ),
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(44.dp))
    }
}
