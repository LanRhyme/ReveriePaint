package com.reverie.paint.ui.painting

import com.reverie.paint.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.reverie.paint.ui.components.ReMenuItem
import com.reverie.paint.ui.theme.Morandi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings panel (top-right dropdown menu, 画世界 Pro style)
 */
@Composable
fun SettingsPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onResetView: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                    .width(320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                    .padding(16.dp)
                    .clickable(enabled = false) {}, // consume clicks
        ) {
            // Top tab icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ReMenuItem(R.drawable.ic_rect, "画布", {}, iconColor = Morandi.accentHi)
                ReMenuItem(R.drawable.ic_fill, "导出", {}, iconColor = Morandi.icon)
                ReMenuItem(R.drawable.ic_settings, "设置", {}, iconColor = Morandi.icon)
                ReMenuItem(R.drawable.ic_home, "云盘", {}, iconColor = Morandi.icon)
            }
            
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
            Spacer(Modifier.height(16.dp))
            
            // Document Info
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            SettingInfoRow("创建时间", sdf.format(Date()))
            SettingInfoRow("画布尺寸", "${vm.docWidth}×${vm.docHeight} - 300ppi")
            SettingInfoRow("一共画了", "0笔")
            SettingInfoRow("作画耗时", "00小时00分钟")
            SettingInfoRow("颜色模式", "RGB颜色")
            SettingInfoRow("文件大小", "3.36KB")
            
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
            Spacer(Modifier.height(16.dp))

            // Action Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReMenuItem(R.drawable.ic_rect, "保存", { vm.saveProject(vm.docName); onClose() })
                ReMenuItem(R.drawable.ic_rect, "另存为", {})
                ReMenuItem(R.drawable.ic_settings, "修复草稿", {})
                ReMenuItem(R.drawable.ic_redo, "云同步", {})
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReMenuItem(R.drawable.ic_rect, "画布调整", {})
                ReMenuItem(R.drawable.ic_rect, "图像调整", {})
                ReMenuItem(R.drawable.ic_rotate_cw, "翻转画布", {})
                ReMenuItem(R.drawable.ic_rotate_ccw, "翻转画布", {})
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                ReMenuItem(R.drawable.ic_rect, "画布盖印", {}, modifier = Modifier.padding(end = 24.dp))
                ReMenuItem(R.drawable.ic_settings, "颜色配置", {})
            }
        }
    }
}

@Composable
private fun SettingInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Morandi.text, fontSize = 13.sp)
    }
}
