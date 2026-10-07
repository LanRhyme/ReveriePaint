/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import com.reverie.paint.core.stylus.FrontBufferProbe
import java.util.concurrent.atomic.AtomicBoolean

/** 单包最大 dab 戳印数 (预分配, 热路径零分配)。 */
private const val MAX_STAMPS = 64

/**
 * 笔尖前沿段预览载荷数据 (传递给 CanvasFrontBufferedRenderer 渲染线程)。
 *
 * [inFlight]: GL 线程绘制进行时为 true。UI 线程复用槽位前必须 CAS 抢占,
 * 绘制中的槽位禁止触碰 —— 底层的 SkPath 非线程安全, UI 线程 `set()` 与
 * GL 线程 `drawPath()` 并发必 SIGSEGV (PR #82 review)。
 */
class FrontBufferPathPacket(
    var path: Path? = null,
    var strokeWidth: Float = 0f,
    var color: Int = 0,
    var isClear: Boolean = false,
    val inFlight: AtomicBoolean = AtomicBoolean(false),
    // ---- STAMP 分级: 真实笔尖戳印预览 ----
    // UI 线程在 CAS 抢占成功后写入, GL 线程只读, happens-before 由 CAS 保证。
    // stampBitmap 为已按笔刷颜色着色的 tip 位图 (BrushTipStampCache 提供),
    // stampX/Y/Size/Alpha 为预分配 dab 点集 (位置像素, 尺寸像素, 透明度 0..1)。
    var stampBitmap: Bitmap? = null,
    val stampX: FloatArray = FloatArray(MAX_STAMPS),
    val stampY: FloatArray = FloatArray(MAX_STAMPS),
    val stampSize: FloatArray = FloatArray(MAX_STAMPS),
    val stampAlpha: FloatArray = FloatArray(MAX_STAMPS),
    var stampCount: Int = 0,
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

    /**
     * 前缓冲渲染器是否真正可用。
     *
     * [CanvasTouchView] 用它做"回退空洞"判定: overlay 对象存在但渲染器初始化失败
     * (探针不满足 / 构造抛异常) 时, `renderPreviewPath` 会静默 no-op, 此时必须让
     * 软件回退路径接管绘制, 否则开启开关后反而完全没有预览。
     */
    val canRenderPreview: Boolean
        get() = isRendererInitialized

    // 热路径零分配：预分配 8 个槽位的路径包环形缓冲与全局单例 clearPacket
    private val packetPool = Array(POOL_SIZE) { FrontBufferPathPacket(Path()) }
    private var poolIndex = 0
    private val clearPacket = FrontBufferPathPacket(isClear = true)

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // STAMP 分级: 戳印绘制 (位图缩放盖印, 预分配零分配)
    private val stampPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stampDst = RectF()

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
                        try {
                            // 1. 单缓冲清屏：擦除前一次预览段
                            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                            if (param.isClear) {
                                return
                            }

                            // 2a. STAMP 分级: 逐 dab 盖印真实笔尖位图
                            if (param.stampCount > 0) {
                                val stampBmp = param.stampBitmap
                                val stampN = param.stampCount.coerceAtMost(MAX_STAMPS)
                                if (stampBmp != null && !stampBmp.isRecycled) {
                                    for (i in 0 until stampN) {
                                        val s = param.stampSize[i]
                                        if (s <= 0f) continue
                                        val cx = param.stampX[i]
                                        val cy = param.stampY[i]
                                        stampPaint.alpha =
                                            (param.stampAlpha[i] * 255f).toInt().coerceIn(0, 255)
                                        val half = s / 2f
                                        stampDst.set(cx - half, cy - half, cx + half, cy + half)
                                        canvas.drawBitmap(stampBmp, null, stampDst, stampPaint)
                                    }
                                }
                                return
                            }

                            // 2b. TIER_1/2: 绘制当前最新笔尖前沿段 (单色折线)
                            val path = param.path ?: return
                            previewPaint.color = param.color
                            previewPaint.strokeWidth = param.strokeWidth.coerceAtLeast(1.5f)
                            canvas.drawPath(path, previewPaint)
                        } finally {
                            // GL 线程绘制完成: 释放槽位, UI 线程方可复用。
                            // commit()/cancelPending() 丢弃的包走 onDrawMultiBufferedLayer 释放。
                            param.inFlight.set(false)
                        }
                    }

                    override fun onDrawMultiBufferedLayer(
                        canvas: Canvas,
                        bufferWidth: Int,
                        bufferHeight: Int,
                        params: Collection<FrontBufferPathPacket>
                    ) {
                        try {
                            // 多缓冲层保持透明清空，正式墨迹由底层的 CanvasTouchView / Krita 负责
                            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        } finally {
                            // commit 静默点 (与前缓冲绘制同一串行 GL 线程, cancelPending 已执行):
                            // 所有 pending 包永不到达前缓冲回调, 在此批量释放 inFlight,
                            // 否则槽位泄漏为永久占用, 打几笔后池枯竭、预览永久消失。
                            for (p in packetPool) p.inFlight.set(false)
                        }
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
     * 原子占用一个空闲槽位。绘制中的槽位跳过; 池空返回 null, 调用方丢帧
     * (预览为 best-effort, 下一触控事件 ~4ms 后即到, 丢帧无视觉影响, 绝不阻塞 UI 线程)。
     */
    private fun obtainPacket(): FrontBufferPathPacket? {
        for (i in 0 until POOL_SIZE) {
            val cand = packetPool[(poolIndex + i) and POOL_MASK]
            if (cand.inFlight.compareAndSet(false, true)) {
                poolIndex = (poolIndex + i + 1) and POOL_MASK
                return cand
            }
        }
        return null
    }

    /**
     * 投递最新前沿预览路径至前缓冲渲染线程 (热路径零分配)。
     */
    fun renderPreviewPath(path: Path, strokeWidth: Float, color: Int) {
        if (!isRendererInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先确认渲染器可用再占槽: 否则占了槽却投递失败, inFlight 永久泄漏。
            @Suppress("UNCHECKED_CAST")
            val renderer = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
                ?: return
            val packet = obtainPacket() ?: return // 无空闲槽位: 丢帧
            try {
                val targetPath = packet.path ?: Path().also { packet.path = it }
                targetPath.set(path)
                packet.strokeWidth = strokeWidth
                packet.color = color
                packet.isClear = false
                packet.stampCount = 0 // 复用槽位: 清除残留戳印
                hasContent = true
                renderer.renderFrontBufferedLayer(packet)
            } catch (t: Throwable) {
                // 提交失败: 立即释放槽位, 否则泄漏为永久 inFlight
                packet.inFlight.set(false)
                Log.w(TAG, "renderPreviewPath error: ${t.message}")
            }
        }
    }

    /**
     * 投递戳印预览: 沿 dab 点集盖印真实笔尖位图 (STAMP 分级, 水彩/纹理/绘画类)。
     * 热路径零分配: 点集拷入预分配槽位数组; 位图由 BrushTipStampCache 提供 (已着色)。
     */
    fun renderStampPreview(
        bitmap: Bitmap,
        xs: FloatArray,
        ys: FloatArray,
        sizes: FloatArray,
        alphas: FloatArray,
        count: Int,
    ) {
        if (!isRendererInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先确认渲染器可用再占槽: 否则占了槽却投递失败, inFlight 永久泄漏。
            @Suppress("UNCHECKED_CAST")
            val renderer = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
                ?: return
            val packet = obtainPacket() ?: return // 无空闲槽位: 丢帧
            try {
                val n = count.coerceAtMost(MAX_STAMPS)
                xs.copyInto(packet.stampX, 0, 0, n)
                ys.copyInto(packet.stampY, 0, 0, n)
                sizes.copyInto(packet.stampSize, 0, 0, n)
                alphas.copyInto(packet.stampAlpha, 0, 0, n)
                packet.stampBitmap = bitmap
                packet.stampCount = n
                packet.path?.rewind()
                packet.isClear = false
                hasContent = true
                renderer.renderFrontBufferedLayer(packet)
            } catch (t: Throwable) {
                // 提交失败: 立即释放槽位, 否则泄漏为永久 inFlight
                packet.inFlight.set(false)
                Log.w(TAG, "renderStampPreview error: ${t.message}")
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
