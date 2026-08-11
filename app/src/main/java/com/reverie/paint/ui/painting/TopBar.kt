package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi

/**
 * Top operation bar (画世界 Pro style):
 * back | canvas name | rotate | zoom | layers | settings
 * Merged with the left rail into one connected dark panel.
 */
@Composable
fun TopBar(
    vm: PaintViewModel,
    onBack: () -> Unit,
    onRotateCw: () -> Unit,
    onRotateCcw: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onLayers: () -> Unit,
    onSettings: () -> Unit,
    onUndo: () -> Unit = { vm.undo() },
    onRedo: () -> Unit = { vm.redo() },
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Morandi.panel)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconBtn("←", "返回", onBack)
        Spacer(Modifier.width(2.dp))
        Text(
            vm.docName,
            color = Morandi.text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        IconBtn("↶", "撤销", onUndo)
        IconBtn("↷", "重做", onRedo)
        IconBtn("↺", "逆时针旋转", onRotateCcw)
        IconBtn("↻", "顺时针旋转", onRotateCw)
        IconBtn("＋", "放大", onZoomIn)
        IconBtn("－", "缩小", onZoomOut)
        IconBtn("◧", "图层", onLayers)
        IconBtn("⚙", "设置", onSettings)
    }
}

/** Small square icon button with tooltip-ish semantics. */
@Composable
fun IconBtn(
    symbol: String,
    desc: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
