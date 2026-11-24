/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import com.reverie.paint.ui.painting.TextInputGuard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
@Composable

internal fun StudioGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            trailing?.invoke()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
internal fun StudioCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
internal fun StudioInnerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(Morandi.border.copy(alpha = 0.1f)),
    )
}

@Composable
internal fun StudioSectionHeader(title: String, textSub: Color) {
    Text(
        text = title,
        color = textSub,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** W-16：当前笔刷下某控件不适用时的说明文字；适用（或 v2 未激活）时返回 null */
@Composable
internal fun studioGateReason(vm: PaintViewModel, id: com.reverie.paint.core.brushstore.ControlId): String? {
    val presetId = vm.brushPresets.firstOrNull { it.index == vm.brushPresetIndex }?.id ?: return null
    val r = com.reverie.paint.core.brushstore.BrushApplicability.forId(presetId)[id] ?: return null
    return stringResource(
        when (r) {
            com.reverie.paint.core.brushstore.BrushApplicability.Reason.MYPAINT -> R.string.brush_gate_mypaint
            com.reverie.paint.core.brushstore.BrushApplicability.Reason.COLORSMUDGE_ONLY -> R.string.brush_gate_colorsmudge_only
            com.reverie.paint.core.brushstore.BrushApplicability.Reason.NO_BRUSH_DEF -> R.string.brush_gate_no_brush_def
            com.reverie.paint.core.brushstore.BrushApplicability.Reason.AUTO_TIP_ONLY -> R.string.brush_gate_auto_tip_only
            com.reverie.paint.core.brushstore.BrushApplicability.Reason.DIRECT_PAINT_ENGINE -> R.string.brush_gate_direct_paint_engine
            com.reverie.paint.core.brushstore.BrushApplicability.Reason.IMAGE_TIP_ONLY -> R.string.brush_gate_image_tip_only
        },
    )
}

/** W-16：不适用时整组控件变灰、拦截触摸，并在上方显示原因；reason 为 null 时原样显示 */
@Composable
internal fun StudioGated(reason: String?, textSub: Color, content: @Composable () -> Unit) {
    if (reason == null) {
        content()
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(reason, color = textSub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 0.38f }) { content() }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

@Composable
internal fun StudioSwitchItem(label: String, checked: Boolean, textMain: Color, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = textMain, fontSize = 13.sp)
        ReSwitch(
            checked = checked,
            onChecked = onCheckedChange,
            modifier = Modifier.scale(0.85f),
        )
    }
}

@Composable
internal fun StudioRadioRow(name: String, selected: Boolean, textMain: Color, textSub: Color, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) Morandi.accent else textMain,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
internal fun StudioSliderItem(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    isPercent: Boolean = false,
    unit: String = "",
    textMain: Color,
    textSub: Color,
    enabled: Boolean = true,
    onChange: (Double) -> Unit,
) {
    val paramUnit = when {
        isPercent -> ParamUnit.PERCENT
        unit == "°" -> ParamUnit.DEGREE
        unit == "px" -> ParamUnit.PIXEL
        else -> ParamUnit.RAW
    }
    ModernParamSlider(
        label = label,
        value = value,
        min = min,
        max = max,
        unit = paramUnit,
        enabled = enabled,
        onChange = onChange,
    )
}

@Composable
internal fun StudioAngleDial(
    angle: Float,
    ratio: Float,
    onAngleChange: (Float) -> Unit,
    cardBg: Color,
    borderCol: Color,
    modifier: Modifier = Modifier,
    tipShape: Int = 0,
) {
    val safeRatio = ratio.coerceIn(0.08f, 1f)
    val normAngle = ((angle % 360f) + 360f) % 360f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.panel.copy(alpha = 0.6f))
            .border(0.6.dp, Morandi.border.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = change.position.x - center.x
                    val dy = change.position.y - center.y
                    var deg = (atan2(dy, dx) * 180f / Math.PI.toFloat())
                    if (deg < 0f) deg += 360f
                    onAngleChange(deg)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f

            // Outer dial ring
            drawCircle(
                color = Morandi.border.copy(alpha = 0.28f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // 12 radial ticks around perimeter
            for (i in 0 until 12) {
                val tickRad = (i * 30f) * (Math.PI.toFloat() / 180f)
                val innerR = radius - if (i % 3 == 0) 6.dp.toPx() else 3.5.dp.toPx()
                drawLine(
                    color = Morandi.border.copy(alpha = if (i % 3 == 0) 0.45f else 0.22f),
                    start = Offset(center.x + cos(tickRad) * innerR, center.y + sin(tickRad) * innerR),
                    end = Offset(center.x + cos(tickRad) * radius, center.y + sin(tickRad) * radius),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Rotated tip silhouette (ellipse or rounded rect scaled by aspect ratio)
            val tipMaxW = radius * 1.18f
            val tipH = (tipMaxW * safeRatio).coerceAtLeast(3.dp.toPx())
            val tipTopLeft = Offset(center.x - tipMaxW / 2f, center.y - tipH / 2f)
            val tipSize = Size(tipMaxW, tipH)
            rotate(degrees = normAngle, pivot = center) {
                if (tipShape == 1) {
                    val cr = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    drawRoundRect(
                        color = Morandi.accent.copy(alpha = 0.22f),
                        topLeft = tipTopLeft,
                        size = tipSize,
                        cornerRadius = cr,
                        style = Fill,
                    )
                    drawRoundRect(
                        color = Morandi.accent.copy(alpha = 0.72f),
                        topLeft = tipTopLeft,
                        size = tipSize,
                        cornerRadius = cr,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                } else {
                    drawOval(
                        color = Morandi.accent.copy(alpha = 0.22f),
                        topLeft = tipTopLeft,
                        size = tipSize,
                        style = Fill,
                    )
                    drawOval(
                        color = Morandi.accent.copy(alpha = 0.72f),
                        topLeft = tipTopLeft,
                        size = tipSize,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
            }

            // Direction indicator needle
            val rad = normAngle * (Math.PI.toFloat() / 180f)
            val needleX = center.x + cos(rad) * (radius - 4.dp.toPx())
            val needleY = center.y + sin(rad) * (radius - 4.dp.toPx())
            drawLine(
                color = Morandi.accent.copy(alpha = 0.85f),
                start = center,
                end = Offset(needleX, needleY),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = Morandi.accent,
                radius = 3.2.dp.toPx(),
                center = Offset(needleX, needleY),
            )
        }
        Text(
            text = "${normAngle.roundToInt()}°",
            color = Morandi.text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Morandi.panel.copy(alpha = 0.8f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
internal fun StudioSegmentedRow(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    enabled: Boolean = true,
    textMain: Color = Morandi.text,
    textSub: Color = Morandi.subText,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) textMain else textSub.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panel.copy(alpha = 0.55f))
                .border(0.6.dp, Morandi.border.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { (id, title) ->
                val isSelected = selected == id
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isSelected) Morandi.accent.copy(alpha = 0.24f) else Color.Transparent)
                        .border(
                            width = if (isSelected) 0.6.dp else 0.dp,
                            color = if (isSelected) Morandi.accent.copy(alpha = 0.7f) else Color.Transparent,
                            shape = RoundedCornerShape(7.dp),
                        )
                        .clickable(enabled = enabled) { onSelect(id) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        color = when {
                            !enabled -> textSub.copy(alpha = 0.5f)
                            isSelected -> Morandi.accent
                            else -> textSub
                        },
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StudioSelectorRow(
    label: String,
    valueText: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    textMain: Color = Morandi.text,
    textSub: Color = Morandi.subText,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = if (enabled) textMain else textSub.copy(alpha = 0.55f),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panel.copy(alpha = 0.65f))
                    .border(0.6.dp, Morandi.border.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = valueText,
                    color = if (enabled) Morandi.accent else textSub.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = textSub,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = textSub,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


