/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

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

    fun createTypeface(family: String, isBold: Boolean, isItalic: Boolean): Typeface {
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val base = when (family) {
            "衬线体", "衬线" -> Typeface.SERIF
            "等宽体", "等宽" -> Typeface.MONOSPACE
            "手写体", "手写", "无衬线体", "黑体" -> Typeface.SANS_SERIF
            else -> Typeface.DEFAULT
        }
        return Typeface.create(base, style)
    }

    fun createTextPaint(cfg: TypographyConfig, opacity: Double = 1.0): TextPaint {
        return TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = createTypeface(cfg.fontFamilyName, cfg.isBold, cfg.isItalic)
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

    fun createLayout(cfg: TypographyConfig, paint: TextPaint, width: Int): StaticLayout {
        val align = when (cfg.alignment) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val displayText = if (cfg.isAllCaps) cfg.text.uppercase() else cfg.text
        val targetWidth = width.coerceAtLeast(40)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
