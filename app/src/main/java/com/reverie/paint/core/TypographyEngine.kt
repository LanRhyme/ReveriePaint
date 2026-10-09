/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.reverie.paint.model.TypographyConfig
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 文本排版与渲染核心引擎 (Typography Engine)
 * 统一管理字体、字距、行高、对齐方式测量与高质量位图渲染
 */
object TypographyEngine {

    fun createTypeface(
        family: String,
        fontPath: String? = null,
        isBold: Boolean = false,
        isItalic: Boolean = false,
        context: Context? = null,
    ): Typeface {
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val custom = FontManager.getTypeface(family, fontPath, context)
        return if (custom != null) {
            if (style != Typeface.NORMAL) Typeface.create(custom, style) else custom
        } else {
            val base = when (family) {
                "衬线体", "衬线", "serif", "system:serif" -> Typeface.SERIF
                "等宽体", "等宽", "monospace", "system:monospace" -> Typeface.MONOSPACE
                "手写体", "手写", "cursive", "system:cursive" -> Typeface.create("cursive", Typeface.NORMAL)
                "无衬线体", "黑体", "sans-serif", "system:sans" -> Typeface.SANS_SERIF
                else -> Typeface.DEFAULT
            }
            Typeface.create(base, style)
        }
    }

    fun createTextPaint(cfg: TypographyConfig, opacity: Double = 1.0, context: Context? = null): TextPaint {
        return TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = createTypeface(cfg.fontFamilyName, cfg.fontPath, cfg.isBold, cfg.isItalic, context)
            textSize = cfg.fontSize.coerceAtLeast(8f)
            isUnderlineText = cfg.isUnderline
            val parsedCol = try {
                Color.parseColor(cfg.textColor)
            } catch (_: Throwable) {
                Color.BLACK
            }
            val alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
            color = Color.argb(
                alpha,
                Color.red(parsedCol),
                Color.green(parsedCol),
                Color.blue(parsedCol),
            )
            if (cfg.fontSize > 0f) {
                letterSpacing = (cfg.letterSpacingSp / cfg.fontSize).coerceIn(-0.2f, 1.0f)
            }
        }
    }

    fun createLayout(cfg: TypographyConfig, paint: TextPaint, width: Int): TypographyDrawLayout {
        if (cfg.isVertical) {
            return VerticalTypographyLayout(cfg, paint, width)
        }
        val align = when (cfg.alignment) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val displayText = if (cfg.isAllCaps) cfg.text.uppercase() else cfg.text
        val targetWidth = width.coerceAtLeast(40)
        val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(displayText, 0, displayText.length, paint, targetWidth)
                .setAlignment(align)
                .setLineSpacing(0f, cfg.lineHeightMultiplier.coerceIn(0.8f, 3.0f))
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                displayText,
                paint,
                targetWidth,
                align,
                cfg.lineHeightMultiplier.coerceIn(0.8f, 3.0f),
                0f,
                true,
            )
        }
        return HorizontalTypographyLayout(staticLayout)
    }

    /**
     * 将排版文本高精度渲染到位图并返回 (Bitmap, docLeft, docTop) 用于图层印制
     */
    fun renderToBitmap(
        cfg: TypographyConfig,
        opacity: Double = 1.0,
    ): Triple<Bitmap, Int, Int>? {
        if (cfg.text.isBlank()) return null
        val paint = createTextPaint(cfg, opacity)
        val targetW = cfg.boxWidth.toInt().coerceAtLeast(60)
        val layout = createLayout(cfg, paint, targetW)
        val w = layout.width.coerceAtLeast(1)
        val h = layout.height.coerceAtLeast(1)

        val pad = 16
        val rot = cfg.rotationDeg

        if (abs(rot) < 0.05f) {
            val bmp = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.translate(pad.toFloat(), pad.toFloat())
            layout.draw(canvas)
            val docLeft = (cfg.posX - pad).toInt()
            val docTop = (cfg.posY - pad).toInt()
            return Triple(bmp, docLeft, docTop)
        }

        // 旋转排版处理
        val srcBmp = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(srcCanvas)

        val matrix = Matrix()
        val cx = (w + pad * 2) / 2f
        val cy = (h + pad * 2) / 2f
        matrix.postRotate(rot, cx, cy)

        val rectF = RectF(0f, 0f, (w + pad * 2).toFloat(), (h + pad * 2).toFloat())
        matrix.mapRect(rectF)
        val outW = ceil(rectF.width()).toInt().coerceAtLeast(1)
        val outH = ceil(rectF.height()).toInt().coerceAtLeast(1)

        val rotBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val rotCanvas = Canvas(rotBmp)
        rotCanvas.translate(-rectF.left, -rectF.top)
        rotCanvas.concat(matrix)
        rotCanvas.drawBitmap(srcBmp, 0f, 0f, null)
        srcBmp.recycle()

        val docCenterX = cfg.posX + w / 2f
        val docCenterY = cfg.posY + h / 2f
        val left = (docCenterX - outW / 2f).toInt()
        val top = (docCenterY - outH / 2f).toInt()
        return Triple(rotBmp, left, top)
    }
}

/**
 * 统一排版布局接口 (支持水平 StaticLayout 与竖排直排布局)
 */
interface TypographyDrawLayout {
    val width: Int
    val height: Int
    fun draw(canvas: Canvas)
}

/**
 * 水平排版包装类
 */
class HorizontalTypographyLayout(private val staticLayout: StaticLayout) : TypographyDrawLayout {
    override val width: Int get() = staticLayout.width
    override val height: Int get() = staticLayout.height
    override fun draw(canvas: Canvas) = staticLayout.draw(canvas)
}

/**
 * 竖向排版布局类 (CJK 传统直排 / 现代竖排)
 */
class VerticalTypographyLayout(
    private val cfg: TypographyConfig,
    private val paint: TextPaint,
    @Suppress("UNUSED_PARAMETER") private val targetWidth: Int,
) : TypographyDrawLayout {

    private data class GlyphPos(val text: String, val x: Float, val y: Float)
    private val glyphs = mutableListOf<GlyphPos>()
    private val layoutWidth: Int
    private val layoutHeight: Int

    override val width: Int get() = layoutWidth
    override val height: Int get() = layoutHeight

    init {
        val displayText = if (cfg.isAllCaps) cfg.text.uppercase() else cfg.text
        val rawLines = displayText.split("\n")
        val lines = if (rawLines.isEmpty()) listOf("") else rawLines

        val fontSize = cfg.fontSize.coerceAtLeast(8f)
        val charStep = (fontSize + cfg.letterSpacingSp).coerceAtLeast(8f)
        val colWidth = (fontSize * cfg.lineHeightMultiplier.coerceIn(0.8f, 3.0f)).coerceAtLeast(10f)

        val fm = paint.fontMetrics
        val baselineInCell = (charStep - (fm.descent - fm.ascent)) * 0.5f - fm.ascent

        val maxChars = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val measuredH = maxOf(40f, maxChars * charStep)
        val measuredW = maxOf(40f, lines.size * colWidth)

        layoutWidth = ceil(measuredW).toInt()
        layoutHeight = ceil(measuredH).toInt()

        for (colIndex in lines.indices) {
            val line = lines[colIndex]
            val colX = if (cfg.verticalRtl) {
                (lines.size - 1 - colIndex) * colWidth
            } else {
                colIndex * colWidth
            }
            val colCenterX = colX + colWidth * 0.5f
            val colTotalH = line.length * charStep

            val startY = when (cfg.alignment) {
                1 -> (measuredH - colTotalH) * 0.5f
                2 -> measuredH - colTotalH
                else -> 0f
            }

            for (charIndex in line.indices) {
                val ch = line[charIndex]
                val chStr = ch.toString()
                val chW = paint.measureText(chStr)

                var posX = colCenterX - chW * 0.5f
                var posY = startY + charIndex * charStep + baselineInCell

                if (ch in "，。、") {
                    posX += chW * 0.28f
                    posY -= charStep * 0.22f
                }

                glyphs.add(GlyphPos(chStr, posX, posY))
            }
        }
    }

    override fun draw(canvas: Canvas) {
        for (g in glyphs) {
            canvas.drawText(g.text, g.x, g.y, paint)
        }
    }
}
