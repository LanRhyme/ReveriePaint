/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.ui.theme.Morandi

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring

internal const val BUTTON_ALPHA = 0.70f

/** 控制条上自绘图标的小圆角按钮 */
@Composable
internal fun GlyphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    draw: DrawScope.() -> Unit,
) {
    val animBg by animateColorAsState(
        targetValue = if (active) Morandi.accent.copy(alpha = 0.28f)
        else Morandi.panelHi.copy(alpha = BUTTON_ALPHA),
        animationSpec = spring(stiffness = 500f),
        label = "glyphBtnBg",
    )
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(animBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp), onDraw = draw)
    }
}

/** 控制条上图文并排的小胶囊按钮 */
@Composable
internal fun GlyphTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    draw: (DrawScope.() -> Unit)? = null,
) {
    val animBg by animateColorAsState(
        targetValue = if (active) Morandi.accent.copy(alpha = 0.28f)
        else Morandi.panelHi.copy(alpha = BUTTON_ALPHA),
        animationSpec = spring(stiffness = 500f),
        label = "glyphTextBtnBg",
    )
    val animText by animateColorAsState(
        targetValue = if (active) Morandi.accent else Morandi.text,
        animationSpec = spring(stiffness = 500f),
        label = "glyphTextBtnText",
    )
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(animBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draw != null) {
            Canvas(modifier = Modifier.size(13.dp), onDraw = draw)
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = animText,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** 控制条上包裹自定义 Composable 图标的小按钮 */
@Composable
internal fun IconButtonBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val animBg by animateColorAsState(
        targetValue = if (active) Morandi.accent.copy(alpha = 0.28f)
        else Morandi.panelHi.copy(alpha = BUTTON_ALPHA),
        animationSpec = spring(stiffness = 500f),
        label = "iconBtnBg",
    )
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(animBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

internal fun DrawScope.drawGlyphPlay() {
    val p = Path().apply {
        moveTo(size.width * 0.22f, size.height * 0.12f)
        lineTo(size.width * 0.86f, size.height * 0.5f)
        lineTo(size.width * 0.22f, size.height * 0.88f)
        close()
    }
    drawPath(p, Morandi.text)
}

internal fun DrawScope.drawGlyphPause() {
    val w = size.width * 0.18f
    drawRect(Morandi.text, Offset(size.width * 0.26f, size.height * 0.16f), Size(w, size.height * 0.68f))
    drawRect(Morandi.text, Offset(size.width * 0.56f, size.height * 0.16f), Size(w, size.height * 0.68f))
}

internal fun DrawScope.drawGlyphPrev() {
    val h = size.height
    drawLine(Morandi.text, Offset(size.width * 0.18f, h * 0.2f), Offset(size.width * 0.18f, h * 0.8f), strokeWidth = 2f)
    val p = Path().apply {
        moveTo(size.width * 0.82f, h * 0.16f)
        lineTo(size.width * 0.34f, h * 0.5f)
        lineTo(size.width * 0.82f, h * 0.84f)
        close()
    }
    drawPath(p, Morandi.text)
}

internal fun DrawScope.drawGlyphNext() {
    val h = size.height
    drawLine(Morandi.text, Offset(size.width * 0.82f, h * 0.2f), Offset(size.width * 0.82f, h * 0.8f), strokeWidth = 2f)
    val p = Path().apply {
        moveTo(size.width * 0.18f, h * 0.16f)
        lineTo(size.width * 0.66f, h * 0.5f)
        lineTo(size.width * 0.18f, h * 0.84f)
        close()
    }
    drawPath(p, Morandi.text)
}

internal fun DrawScope.drawGlyphPlus() {
    drawLine(Morandi.text, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth = 2f)
}

internal fun DrawScope.drawGlyphMinus() {
    drawLine(Morandi.text, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth = 2f)
}

internal fun DrawScope.drawGlyphSettings() {
    val knob = Size(size.height * 0.22f, size.height * 0.22f)
    val rows = listOf(0.24f, 0.5f, 0.76f)
    val knobs = listOf(0.62f, 0.34f, 0.7f)
    rows.forEachIndexed { i, y ->
        val cy = size.height * y
        drawLine(Morandi.text, Offset(size.width * 0.15f, cy), Offset(size.width * 0.85f, cy), strokeWidth = 1.6f)
        val kx = size.width * knobs[i] - knob.width / 2f
        drawRoundRect(Morandi.text, Offset(kx, cy - knob.height / 2f), knob, CornerRadius(knob.width / 2f))
    }
}

internal fun DrawScope.drawGlyphClose() {
    drawLine(Morandi.text, Offset(size.width * 0.22f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.78f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.78f, size.height * 0.22f), Offset(size.width * 0.22f, size.height * 0.78f), strokeWidth = 2f)
}

internal fun DrawScope.drawGlyphDuplicate() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.08f, size.height * 0.22f), Size(size.width * 0.58f, size.height * 0.56f))
    drawRoundRectCompat(
        Morandi.text.copy(alpha = 0.45f),
        Offset(size.width * 0.34f, size.height * 0.22f),
        Size(size.width * 0.58f, size.height * 0.56f),
    )
}

internal fun DrawScope.drawGlyphTrash() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.16f, size.height * 0.62f), Size(size.width * 0.68f, size.height * 0.24f))
    drawLine(Morandi.text, Offset(size.width * 0.1f, size.height * 0.28f), Offset(size.width * 0.9f, size.height * 0.28f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.36f, size.height * 0.12f), Offset(size.width * 0.64f, size.height * 0.12f), strokeWidth = 2f)
}

internal fun DrawScope.drawGlyphSelect() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.15f, size.height * 0.15f), Size(size.width * 0.7f, size.height * 0.7f))
    drawRoundRectCompat(Morandi.accent, Offset(size.width * 0.35f, size.height * 0.35f), Size(size.width * 0.3f, size.height * 0.3f))
}

internal fun DrawScope.drawGlyphShiftLeft() {
    val h = size.height
    val p = Path().apply {
        moveTo(size.width * 0.7f, h * 0.2f)
        lineTo(size.width * 0.25f, h * 0.5f)
        lineTo(size.width * 0.7f, h * 0.8f)
        close()
    }
    drawPath(p, Morandi.text)
}

internal fun DrawScope.drawGlyphShiftRight() {
    val h = size.height
    val p = Path().apply {
        moveTo(size.width * 0.3f, h * 0.2f)
        lineTo(size.width * 0.75f, h * 0.5f)
        lineTo(size.width * 0.3f, h * 0.8f)
        close()
    }
    drawPath(p, Morandi.text)
}

internal fun DrawScope.drawRoundRectCompat(
    color: Color,
    topLeft: Offset,
    size: Size,
) {
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(3f, 3f),
    )
}

internal fun DrawScope.drawGlyphOnionSkin() {
    val r = size.width * 0.28f
    val cy = size.height * 0.5f
    drawCircle(
        color = Morandi.text.copy(alpha = 0.40f),
        radius = r,
        center = Offset(size.width * 0.38f, cy),
    )
    drawCircle(
        color = Morandi.text,
        radius = r,
        center = Offset(size.width * 0.62f, cy),
    )
}

internal fun DrawScope.drawGlyphFlip() {
    val h = size.height
    val w = size.width
    drawRoundRectCompat(Morandi.text.copy(alpha = 0.45f), Offset(w * 0.15f, h * 0.18f), Size(w * 0.45f, h * 0.64f))
    drawRoundRectCompat(Morandi.text, Offset(w * 0.40f, h * 0.18f), Size(w * 0.45f, h * 0.64f))
}

internal fun DrawScope.drawGlyphShiftTrace() {
    val h = size.height
    val w = size.width
    val strokeWidth = 1.4f
    drawRoundRect(
        color = Morandi.text.copy(alpha = 0.40f),
        topLeft = Offset(w * 0.12f, h * 0.12f),
        size = Size(w * 0.55f, h * 0.55f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
    )
    drawRoundRect(
        color = Morandi.text,
        topLeft = Offset(w * 0.33f, h * 0.33f),
        size = Size(w * 0.55f, h * 0.55f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
    )
}
