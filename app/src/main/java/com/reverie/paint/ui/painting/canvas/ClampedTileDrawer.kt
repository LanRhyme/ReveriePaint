/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader

/**
 * 512 显示瓦片各自是一张位图。`drawBitmap` 开双线性过滤时, 边上的采样会读到位图外面,
 * 外面按透明计, 底下的棋盘格就透成一张网格。
 *
 * 用 CLAMP 模式的 [BitmapShader] 把边缘重复成该瓦片自己的最后一个像素: 过滤仍然开着,
 * 但不再采到透明。着色器按瓦片缓存, 位图引用或 `generationId` 变了才重建 (后备池里的
 * 位图会被换回同一格, 只比引用会复用旧快照)。
 *
 * 软件 Canvas 每次都直接读位图最新像素且边缘自带 clamp, 直接走 `drawBitmap`。
 * 关闭插值时最近邻采样不会越界, 同样直接 `drawBitmap`。
 */
internal class ClampedTileDrawer {
    private val shaderPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }
    private val filterPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }
    private val rawPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    private val matrix = Matrix()
    private var shaders = arrayOfNulls<BitmapShader>(0)
    private var bound = arrayOfNulls<Bitmap>(0)
    private var genIds = IntArray(0)

    fun draw(
        canvas: Canvas,
        index: Int,
        tileCount: Int,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        filter: Boolean,
    ) {
        if (index < 0 || index >= tileCount || bitmap.isRecycled) return
        if (!filter) {
            canvas.drawBitmap(bitmap, left, top, rawPaint)
            return
        }
        if (!canvas.isHardwareAccelerated) {
            canvas.drawBitmap(bitmap, left, top, filterPaint)
            return
        }
        if (shaders.size != tileCount) {
            shaders = arrayOfNulls(tileCount)
            bound = arrayOfNulls(tileCount)
            genIds = IntArray(tileCount) { -1 }
        }
        val curGen = bitmap.generationId
        var shader = shaders[index]
        if (shader == null || bound[index] !== bitmap || genIds[index] != curGen) {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaders[index] = shader
            bound[index] = bitmap
            genIds[index] = curGen
        }
        matrix.setTranslate(left, top)
        shader.setLocalMatrix(matrix)
        shaderPaint.shader = shader
        canvas.drawRect(left, top, left + bitmap.width, top + bitmap.height, shaderPaint)
    }

    fun clear() {
        if (shaders.isEmpty()) return
        shaders = arrayOfNulls(0)
        bound = arrayOfNulls(0)
        genIds = IntArray(0)
    }
}
