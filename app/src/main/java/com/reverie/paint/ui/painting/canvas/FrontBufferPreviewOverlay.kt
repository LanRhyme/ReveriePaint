/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import com.reverie.paint.core.stylus.FrontBufferProbe

/**
 * 笔尖前沿段预览载荷数据 (传递给 CanvasFrontBufferedRenderer 渲染线程)。
 */
class FrontBufferPathPacket(
    var path: Path? = null,
    var strokeWidth: Float = 0f,
    var color: Int = 0,
    var isClear: Boolean = false,
)

/**
 * 轻量独立透明前缓冲预览层 (SurfaceView + CanvasFrontBufferedRenderer)。
 * 仅负责手写笔落笔中 (DOWN..UP) 笔尖前沿段的单缓冲 4~8ms 直出呈现。
 * 彻底隔离触摸事件，保证触摸 100% 穿透到底层 CanvasTouchView。
 */
class FrontBufferPreviewOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    companion object {
        private const val TAG = "FrontBufferOverlay"
        private const val POOL_SIZE = 8
        private const val POOL_MASK = POOL_SIZE - 1
    }

    private var frontRenderer: Any? = null // 保持引用以兼容 API < 29
    private var isRendererInitialized = false
    private var hasContent = false

    // 热路径零分配：预分配 8 个槽位的路径包环形缓冲与全局单例 clearPacket
    private val packetPool = Array(POOL_SIZE) { FrontBufferPathPacket(Path()) }
    private var poolIndex = 0
    private val clearPacket = FrontBufferPathPacket(isClear = true)

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        // 设为完全透明与顶层覆盖
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = false
        isClickable = false

        if (FrontBufferProbe.isSupported()) {
            initRenderer()
        }
    }

    private fun initRenderer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val callback = object : CanvasFrontBufferedRenderer.Callback<FrontBufferPathPacket> {
                    override fun onDrawFrontBufferedLayer(
                        canvas: Canvas,
                        bufferWidth: Int,
                        bufferHeight: Int,
                        param: FrontBufferPathPacket
                    ) {
                        // 1. 单缓冲清屏：擦除前一次预览段
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                        if (param.isClear || param.path == null) {
                            return
                        }

                        // 2. 绘制当前最新笔尖前沿段
                        previewPaint.color = param.color
                        previewPaint.strokeWidth = param.strokeWidth.coerceAtLeast(1.5f)
                        canvas.drawPath(param.path!!, previewPaint)
                    }

                    override fun onDrawMultiBufferedLayer(
                        canvas: Canvas,
                        bufferWidth: Int,
                        bufferHeight: Int,
                        params: Collection<FrontBufferPathPacket>
                    ) {
                        // 多缓冲层保持透明清空，正式墨迹由底层的 CanvasTouchView / Krita 负责
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    }
                }

                frontRenderer = CanvasFrontBufferedRenderer(this, callback)
                isRendererInitialized = true
                Log.d(TAG, "CanvasFrontBufferedRenderer initialized successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize CanvasFrontBufferedRenderer", t)
                frontRenderer = null
                isRendererInitialized = false
            }
        }
    }

    /**
     * 投递最新前沿预览路径至前缓冲渲染线程 (热路径零分配)。
     */
    fun renderPreviewPath(path: Path, strokeWidth: Float, color: Int) {
        if (!isRendererInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val packet = packetPool[poolIndex]
                poolIndex = (poolIndex + 1) and POOL_MASK
                val targetPath = packet.path ?: Path().also { packet.path = it }
                targetPath.set(path)
                packet.strokeWidth = strokeWidth
                packet.color = color
                packet.isClear = false
                hasContent = true
                @Suppress("UNCHECKED_CAST")
                (frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>)
                    ?.renderFrontBufferedLayer(packet)
            } catch (t: Throwable) {
                Log.w(TAG, "renderPreviewPath error: ${t.message}")
            }
        }
    }

    /**
     * 抬笔或取消时清空前缓冲层。
     */
    fun clearPreview() {
        if (!isRendererInitialized) return
        if (!hasContent) return
        hasContent = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                @Suppress("UNCHECKED_CAST")
                val r = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
                r?.renderFrontBufferedLayer(clearPacket)
                r?.commit()
            } catch (t: Throwable) {
                Log.w(TAG, "clearPreview error: ${t.message}")
            }
        }
    }

    /**
     * 释放前缓冲渲染器资源。
     */
    fun release() {
        hasContent = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                @Suppress("UNCHECKED_CAST")
                (frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>)
                    ?.release(true)
            } catch (t: Throwable) {
                Log.w(TAG, "release error: ${t.message}")
            }
        }
        frontRenderer = null
        isRendererInitialized = false
    }

    var targetTouchView: CanvasTouchView? = null

    // 触摸与悬浮事件 100% 穿透并转发到底层 CanvasTouchView
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchGenericMotionEvent(event)
    }
}
