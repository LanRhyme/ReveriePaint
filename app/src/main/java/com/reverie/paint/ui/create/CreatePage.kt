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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.CanvasPresets
import com.reverie.paint.ui.theme.ColorSwatches
import com.reverie.paint.ui.theme.parseColor

@Composable
fun CreatePage(vm: PaintViewModel) {
    val colors = Theme.current
    var w by remember { mutableStateOf("1080") }
    var h by remember { mutableStateOf("1920") }
    var showCustom by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bg)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = colors.text, modifier = Modifier.clickable { vm.goHome() }.padding(8.dp).size(20.dp))
            Spacer(Modifier.weight(1f))
            Text("新建画布", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Science, contentDescription = "Lab", tint = colors.subText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("实验室", color = colors.subText, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ActionIcon(Icons.Default.Add, "自定义", onClick = { showCustom = true })
            ActionIcon(Icons.Default.PlayArrow, "动画", onClick = {})
            ActionIcon(Icons.Default.Image, "打开图片", onClick = {})
            ActionIcon(Icons.Default.Star, "模板", onClick = {})
        }

        Spacer(Modifier.height(24.dp))

        if (showCustom) {
            // Custom Size Form
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("自定义尺寸", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SizeField("宽 (px)", w, { w = it }, Modifier.weight(1f))
                    SizeField("高 (px)", h, { h = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent).clickable {
                        val ww = w.toIntOrNull()?.coerceIn(64, 8192) ?: 1080
                        val hh = h.toIntOrNull()?.coerceIn(64, 8192) ?: 1920
                        vm.startPainting(ww, hh)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text("创建", color = colors.onAccent, fontSize = 16.sp)
                }
            }
        } else {
            // Tabs
            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("预设画布", color = colors.accent, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(40.dp).height(2.dp).background(colors.accent))
                }
                Spacer(Modifier.width(20.dp))
                Text("我的画布", color = colors.subText, fontSize = 14.sp)
            }

            Spacer(Modifier.height(16.dp))

            // Presets List
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mock realistic presets based on image
                val realisticPresets = listOf(
                    Triple("9:16", 1080 to 1920, 474),
                    Triple("3:4", 1080 to 1440, 634),
                    Triple("1:1", 1080 to 1080, 847),
                    Triple("16:9", 1920 to 1080, 1513),
                    Triple("4:3", 1440 to 1080, 1132),
                    Triple("屏幕", 1080 to 2400, 378),
                    Triple("A4", 2480 to 3508, 108),
                    Triple("A5", 1748 to 2480, 223)
                )

                realisticPresets.forEach { (name, size, layers) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(colors.panel).clickable {
                            vm.startPainting(size.first, size.second)
                        }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CropPortrait, contentDescription = null, tint = colors.icon, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(name, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("sRGB", color = colors.subText, fontSize = 10.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("${size.first}×${size.second}px", color = colors.subText, fontSize = 12.sp, modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
                        Spacer(Modifier.width(16.dp))
                        Text("${layers}图层", color = colors.subText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = Theme.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp)).background(colors.panel),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = colors.text, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = colors.text, fontSize = 12.sp)
    }
}

@Composable
private fun SizeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Theme.current
    Column(modifier) {
        Text(label, color = colors.subText, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { v -> if (v.length <= 5) onChange(v.filter { it.isDigit() }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.panel,
                unfocusedContainerColor = colors.panel,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
    }
}
