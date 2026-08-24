/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/** 设备是否支持真实背景模糊（RenderEffect 需 API 31+） */
val deviceSupportsBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 玻璃拟态统一样式工厂。全 app 浮层只允许经此创建 [HazeStyle]，
 * 保证 blurRadius / noiseFactor / 色调全局一致。
 *
 * 降级：调用方 gating `vm.blurBackground && hazeState != null` 之外，
 * 不支持设备已在 PaintViewModel 单点强制关闭（见 syncSettingsFromPrefs），
 * 走实色半透明 + [glassBorder] 的质感降级路径。
 */
object Glass {
    val blurRadius = 24.dp
    private const val noiseFactor = 0.02f

    /** 浮层条：顶栏/工具滑轨/侧栏大面板/底部栏 */
    @Composable
    fun barStyle(alpha: Float = 0.78f): HazeStyle = style(alpha)

    /** 弹出层：对话框/浮动弹窗，更透更虚 */
    @Composable
    fun popupStyle(alpha: Float = 0.66f): HazeStyle = style(alpha)

    @Composable
    private fun style(alpha: Float): HazeStyle {
        val c = Morandi.panel.copy(alpha = alpha.coerceIn(0.05f, 0.98f))
        return remember(c) {
            HazeStyle(
                backgroundColor = c,
                tint = HazeTint(c),
                blurRadius = blurRadius,
                noiseFactor = noiseFactor,
            )
        }
    }
}

/**
 * 方向性玻璃描边：上缘受光（白 α0.14）渐隐至下缘背光（黑 α0.06），
 * 替代纯色 border 的塑料感。hairline 1dp。Brush/Stroke 均在组合期预分配。
 */
fun Modifier.glassBorder(shape: Shape): Modifier = composed {
    val brush = remember {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.02f),
                Color.Black.copy(alpha = 0.06f),
            )
        )
    }
    val stroke = with(LocalDensity.current) { remember { Stroke(width = 1.dp.toPx()) } }
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, brush = brush, style = stroke)
    }
}
