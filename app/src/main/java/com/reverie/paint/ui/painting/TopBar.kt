package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.theme.Morandi

/**
 * Top operation bar
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
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
            modifier
                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Default)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 16.dp))
                .background(Morandi.panel.copy(alpha = opacity))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReIconButton(R.drawable.ic_undo, "撤销", onUndo)
        ReIconButton(R.drawable.ic_redo, "重做", onRedo)
        ReIconButton(R.drawable.ic_layers, "图层", onLayers)
        ReIconButton(R.drawable.ic_settings, "设置", onSettings)
        ReIconButton(R.drawable.ic_x, "关闭", onBack) // Moved to the right
    }
}
