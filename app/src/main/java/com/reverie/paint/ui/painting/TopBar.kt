package com.reverie.paint.ui.painting

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.theme.Morandi

/**
 * Top operation bar (画世界 Pro style) with Tabler icon-pack buttons:
 * home | canvas name | undo/redo | rotate | zoom | layers | settings
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
                .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReIconButton(R.drawable.ic_home, "返回主页", onBack)
        Spacer(Modifier.width(4.dp))
        Text(
            vm.docName,
            color = Morandi.text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ReIconButton(R.drawable.ic_undo, "撤销", onUndo)
        ReIconButton(R.drawable.ic_redo, "重做", onRedo)
        ReIconButton(R.drawable.ic_rotate_ccw, "逆时针旋转", onRotateCcw)
        ReIconButton(R.drawable.ic_rotate_cw, "顺时针旋转", onRotateCw)
        ReIconButton(R.drawable.ic_zoom_in, "放大", onZoomIn)
        ReIconButton(R.drawable.ic_zoom_out, "缩小", onZoomOut)
        ReIconButton(R.drawable.ic_layers, "图层", onLayers)
        ReIconButton(R.drawable.ic_settings, "设置", onSettings)
    }
}


