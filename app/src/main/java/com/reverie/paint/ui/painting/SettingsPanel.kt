package com.reverie.paint.ui.painting

import com.reverie.paint.R
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.components.RePanel

/**
 * Settings panel (bottom sheet): canvas info, view reset, document name.
 */
@Composable
fun SettingsPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onResetView: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RePanel(title = "设置", onClose = onClose, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))

            Spacer(Modifier.height(14.dp))

            SettingRow("画布名称", vm.docName)
            SettingRow("画布尺寸", "${vm.docWidth} × ${vm.docHeight}")
            SettingRow("图层数", "${vm.layerCount}")

            Spacer(Modifier.height(14.dp))

            // Save project
            ReButton(
                text = "保存项目",
                onClick = {
                    vm.saveProject(vm.docName)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            // Reset view
            ReButton(
                text = "重置视图",
                onClick = {
                    onResetView()
                    onClose()
                },
                primary = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Morandi.text, fontSize = 13.sp)
    }
}
