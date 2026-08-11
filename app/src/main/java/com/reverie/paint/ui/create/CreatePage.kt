package com.reverie.paint.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.CanvasPresets
import com.reverie.paint.ui.theme.ColorSwatches
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor

@Composable
fun CreatePage(vm: PaintViewModel) {
    var w by remember { mutableStateOf("1080") }
    var h by remember { mutableStateOf("1920") }
    var bg by remember { mutableStateOf("#F2F0EA") }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Morandi.bg)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBtn("←", onTap = { vm.goHome() })
            Spacer(Modifier.weight(1f))
            Text("新建画布", color = Morandi.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(38.dp))
        }

        Spacer(Modifier.height(20.dp))

        // Presets
        Text("画布尺寸", color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))

        val presets = CanvasPresets.chunked(2)
        for (row in presets) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (preset in row) {
                    PresetCard(
                        name = preset.name,
                        sizeText = "${preset.width}×${preset.height}",
                        selected = w == preset.width.toString() && h == preset.height.toString(),
                        onTap = {
                            w = preset.width.toString()
                            h = preset.height.toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(12.dp))

        // Custom size
        Text("自定义", color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SizeField("宽", w, { w = it }, Modifier.weight(1f))
            SizeField("高", h, { h = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("$w × $h px", color = Morandi.subText, fontSize = 12.sp)

        Spacer(Modifier.height(20.dp))

        // Background color
        Text("背景颜色", color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (c in ColorSwatches) {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(parseColor(c))
                            .border(2.dp, if (bg == c) Morandi.accentHi else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { bg = c },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Start
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.accent)
                    .clickable {
                        val ww = w.toIntOrNull()?.coerceIn(64, 8192) ?: 1080
                        val hh = h.toIntOrNull()?.coerceIn(64, 8192) ?: 1920
                        vm.startPainting(ww, hh)
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text("开始绘画", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetCard(
    name: String,
    sizeText: String,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .height(86.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Morandi.panelHi else Morandi.panel)
                .border(
                    1.dp,
                    if (selected) Morandi.accentHi else Morandi.border,
                    RoundedCornerShape(12.dp),
                ).clickable { onTap() }
                .padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(name, color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(sizeText, color = Morandi.subText, fontSize = 11.sp)
    }
}

@Composable
private fun SizeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = Morandi.subText, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { v -> if (v.length <= 5) onChange(v.filter { it.isDigit() }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle =
                androidx.compose.ui.text.TextStyle(
                    color = Morandi.text,
                    fontSize = 14.sp,
                ),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Morandi.accent,
                    unfocusedBorderColor = Morandi.border,
                    focusedContainerColor = Morandi.panel,
                    unfocusedContainerColor = Morandi.panel,
                    cursorColor = Morandi.accent,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
        )
    }
}

@Composable
private fun TopBtn(
    text: String,
    onTap: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panel)
                .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Morandi.text, fontSize = 16.sp)
    }
}
