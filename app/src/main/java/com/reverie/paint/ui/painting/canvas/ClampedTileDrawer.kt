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
    private val srcRect = android.graphics.Rect()
    private val dstRectF = android.graphics.RectF()
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
    ) = draw(canvas, index, tileCount, bitmap, left, top, bitmap.width.toFloat(), bitmap.height.toFloat(), filter)

    /**
     * 把瓦片画进 [dstW] x [dstH] 的矩形: 视口 LoD 下瓦片位图是缩小过的 (文档像素 /
     * docScale), 必须拉伸回它在文档空间里的尺寸, 否则整幅画布会缩小成一角。
     */
    fun draw(
        canvas: Canvas,
        index: Int,
        tileCount: Int,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        dstW: Float,
        dstH: Float,
        filter: Boolean,
    ) {
        if (index < 0 || index >= tileCount || bitmap.isRecycled) return
        if (dstW <= 0f || dstH <= 0f) return
        val sameSize = dstW == bitmap.width.toFloat() && dstH == bitmap.height.toFloat()
        if (!filter) {
            if (sameSize) {
                canvas.drawBitmap(bitmap, left, top, rawPaint)
            } else {
                srcRect.set(0, 0, bitmap.width, bitmap.height)
                dstRectF.set(left, top, left + dstW, top + dstH)
                canvas.drawBitmap(bitmap, srcRect, dstRectF, rawPaint)
            }
            return
        }
        if (!canvas.isHardwareAccelerated) {
            if (sameSize) {
                canvas.drawBitmap(bitmap, left, top, filterPaint)
            } else {
                srcRect.set(0, 0, bitmap.width, bitmap.height)
                dstRectF.set(left, top, left + dstW, top + dstH)
                canvas.drawBitmap(bitmap, srcRect, dstRectF, filterPaint)
            }
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
        if (sameSize) {
            matrix.setTranslate(left, top)
        } else {
            // 着色器坐标是位图像素: 先按目标矩形缩放, 再平移到落点
            matrix.setScale(dstW / bitmap.width.toFloat(), dstH / bitmap.height.toFloat())
            matrix.postTranslate(left, top)
        }
        shader.setLocalMatrix(matrix)
        shaderPaint.shader = shader
        canvas.drawRect(left, top, left + dstW, top + dstH, shaderPaint)
    }

    fun clear() {
        if (shaders.isEmpty()) return
        shaders = arrayOfNulls(0)
        bound = arrayOfNulls(0)
        genIds = IntArray(0)
    }
}
