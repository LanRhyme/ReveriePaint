/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.Choreographer
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi

data class ScratchPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val rotation: Float = 0f,
    val timeMs: Long = 0L,
)

/**
 * 将试画板内容生成为 256x256 的预设缩略图
 * 优先裁剪试画板上的真实位图笔迹；若试画板为空，则绘制经典的优雅弧线笔触
 */
internal fun captureScratchpadAsThumbnail(
    context: Context,
    vm: PaintViewModel,
    scratchBitmap: Bitmap?,
    strokes: List<List<ScratchPoint>>,
) {
    val size = 256
    val outBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(outBitmap)
    // 雅致的浅色卡片底色
    canvas.drawColor(android.graphics.Color.rgb(243, 242, 240))

    val brushColorInt = runCatching { android.graphics.Color.parseColor(vm.brushColor) }.getOrDefault(android.graphics.Color.DKGRAY)

    if (scratchBitmap != null) {
        val bounds = findNonTransparentBounds(scratchBitmap)
        if (bounds != null && bounds.width() > 6 && bounds.height() > 6) {
            val pad = 32f
            val targetBox = size - pad * 2f
            val scale = minOf(targetBox / bounds.width(), targetBox / bounds.height()).coerceIn(0.1f, 4.0f)
            val dstW = bounds.width() * scale
            val dstH = bounds.height() * scale
            val left = pad + (targetBox - dstW) / 2f
            val top = pad + (targetBox - dstH) / 2f
            val srcRect = Rect(bounds)
            val dstRect = RectF(left, top, left + dstW, top + dstH)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(scratchBitmap, srcRect, dstRect, paint)
            vm.capturePresetThumbnail(outBitmap)
            return
        }
    }

    // 兜底：若试画板无有效笔迹，绘制经典平滑笔画
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = brushColorInt
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = (vm.brushSize.toFloat()).coerceIn(12f, 36f)
        alpha = (vm.brushOpacity * 255).toInt().coerceIn(40, 255)
    }

    val path = android.graphics.Path()
    path.moveTo(40f, 216f)
    path.cubicTo(80f, 160f, 176f, 100f, 216f, 40f)
    canvas.drawPath(path, paint)

    vm.capturePresetThumbnail(outBitmap)
}

/** 查找非透明像素的外接包围盒 */
private fun findNonTransparentBounds(bitmap: Bitmap): Rect? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null

    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1

    val pixels = IntArray(w)
    for (y in 0 until h) {
        bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val alpha = (pixels[x] ushr 24) and 0xFF
            if (alpha > 12) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }

    return if (maxX >= minX && maxY >= minY) {
        Rect(minX, minY, maxX + 1, maxY + 1)
    } else {
        null
    }
}

/** 试画板指针跟踪状态（UI 线程专用，用普通字段避免热路径快照写开销） */
private class ScratchpadPointerState {
    var pointerId: Int = -1
    var initPressure: Float = 1f
    var lastX: Float = Float.NaN
    var lastY: Float = Float.NaN

    var effTiltX: Float = 0f
    var effTiltY: Float = 0f

    fun reset() {
        pointerId = -1
        initPressure = 1f
        lastX = Float.NaN
        lastY = Float.NaN
    }
}

/** 收笔尾锋压感下限，与主画布同口径。 */
private const val SCRATCHPAD_TAIL_PRESSURE_FLOOR = 0.05f

/** 倾角分量钳制到 [-60, 60]，非有限值归零。 */
private fun clampScratchpadTilt(v: Float): Float =
    if (v.isFinite()) v.coerceIn(-60f, 60f) else 0f

/** 每个采样点的有效倾角 (度): 硬件倾角直通; 手指/零倾角笔为 0 (与 Krita 一致)。热路径零分配。 */
@Suppress("UNUSED_PARAMETER")
private fun resolveScratchpadTilt(
    state: ScratchpadPointerState,
    x: Float,
    y: Float,
    tiltRad: Float,
    orientationRad: Float,
) {
    val hw = scratchpadTilt(tiltRad, orientationRad)
    state.effTiltX = clampScratchpadTilt(hw.first)
    state.effTiltY = clampScratchpadTilt(hw.second)
}

/** 压力取值：设备不报压力（<=0）时回退 fallback，口径与主画布一致 */
private fun scratchpadRawPressure(p: Float, fallback: Float): Float =
    if (p > 0f) p.coerceIn(0.01f, 1.0f) else fallback

/**
 * tilt(弧度) + orientation(弧度) → tiltX/tiltY（度）。
 * 与主画布 CanvasTouchView.updateStylusSensors 同款换算；试画板无画布旋转，方位角不再减 canvasRotRad。
 */
private fun scratchpadTilt(tiltRad: Float, orientationRad: Float): Pair<Float, Float> {
    if (tiltRad.isNaN() || tiltRad <= 0.0001f) return 0f to 0f
    if (orientationRad.isNaN()) return 0f to 0f
    val tiltDeg = (tiltRad.toDouble() * (180.0 / Math.PI)).coerceIn(0.0, 60.0)
    val tx = (Math.sin(orientationRad.toDouble()) * tiltDeg).coerceIn(-60.0, 60.0)
    val ty = (-Math.cos(orientationRad.toDouble()) * tiltDeg).coerceIn(-60.0, 60.0)
    return tx.toFloat() to ty.toFloat()
}

/** 压力先过全局校准（与画布 touchStart 的 computeEffectivePressure 同款）再构造 ScratchPoint */
private fun scratchpadPoint(
    vm: PaintViewModel,
    x: Float,
    y: Float,
    rawPressure: Float,
    tiltX: Float,
    tiltY: Float,
    timeMs: Long = 0L,
): ScratchPoint {
    val eff = vm.computeEffectivePressure(rawPressure.toDouble()).toFloat().coerceIn(0.01f, 1f)
    return ScratchPoint(x, y, eff, tiltX, tiltY, 0f, timeMs)
}

/** 当前点: 先解析有效倾角 (硬件透传/合成) 再构造 */
private fun scratchpadPoint(
    vm: PaintViewModel,
    state: ScratchpadPointerState,
    event: MotionEvent,
    idx: Int,
    rawPressure: Float,
): ScratchPoint {
    resolveScratchpadTilt(
        state,
        event.getX(idx),
        event.getY(idx),
        event.getAxisValue(MotionEvent.AXIS_TILT, idx),
        event.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx),
    )
    return scratchpadPoint(vm, event.getX(idx), event.getY(idx), rawPressure, state.effTiltX, state.effTiltY, event.eventTime)
}

/** 历史点: 用历史坐标/历史倾角轴解析后构造 */
private fun scratchpadHistoricalPoint(
    vm: PaintViewModel,
    state: ScratchpadPointerState,
    event: MotionEvent,
    idx: Int,
    h: Int,
    rawPressure: Float,
): ScratchPoint {
    val hx = event.getHistoricalX(idx, h)
    val hy = event.getHistoricalY(idx, h)
    resolveScratchpadTilt(
        state,
        hx,
        hy,
        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h),
        event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, idx, h),
    )
    return scratchpadPoint(vm, hx, hy, rawPressure, state.effTiltX, state.effTiltY, event.getHistoricalEventTime(h))
}

/**
 * 试画板流线平滑（单点）：与主画布指数稳定器同款公式
 * （PaintViewModelTools.touchMove 的 strokeStabilizer/brushStreamline 分支）。
 * [s] 为流线强度（vm.brushStreamline, 0..1）；s<=0.001 恒等直通（不引入任何额外计算路径）；
 * 自适应项与 clamp 照抄主画布口径（distToTarget 用平滑点与原始点的距离）。
 * 纯函数，供 JVM 单测。
 */
internal fun scratchpadStreamline(
    s: Double,
    smoothedX: Double,
    smoothedY: Double,
    targetX: Float,
    targetY: Float,
): Pair<Double, Double> {
    if (s <= 0.001) return targetX.toDouble() to targetY.toDouble()
    val stabFactor = s.coerceIn(0.0, 1.0)
    val baseAlpha = kotlin.math.exp(-stabFactor * 5.2) * 0.994 + 0.006
    val distToTarget = Math.hypot((targetX - smoothedX).toDouble(), (targetY - smoothedY).toDouble())
    val adaptiveBoost = (distToTarget / 150.0).coerceIn(0.0, 1.0) * 0.015
    val alpha = (baseAlpha + adaptiveBoost).toFloat().coerceIn(0.006f, 1.0f)
    val nx = smoothedX + (targetX - smoothedX) * alpha
    val ny = smoothedY + (targetY - smoothedY) * alpha
    return nx to ny
}

/**
 * 试画板流线平滑会话状态（UI 线程专用，普通字段避免热路径开销）。
 * sx/sy 为当前平滑坐标；lastRaw 记录最近一个原始采样点，供收尾把平滑点推到最终目标点。
 */
private class ScratchpadSmoothSession {
    var sx: Double = 0.0
    var sy: Double = 0.0
    var lastRaw: ScratchPoint? = null

    fun start(x: Float, y: Float) {
        sx = x.toDouble()
        sy = y.toDouble()
        lastRaw = null
    }

    fun reset() {
        lastRaw = null
    }
}

/**
 * 试画板渲染合帧门闩（纯 Kotlin，无 Android 依赖，供 JVM 单测）：
 * 笔画中的每批采样只置脏标记并经 [postFrame]（生产环境对接 Choreographer VSync）合帧，
 * 每帧至多触发一次 [enqueueRender] 像素读回；读回在途期间丢弃积压请求，
 * onDone 后若仍有新脏标记再补挂恰好一次。
 */
internal class ScratchpadRenderCoalescer(
    private val postFrame: (() -> Unit) -> Unit,
    private val enqueueRender: (onDone: () -> Unit) -> Unit,
) {
    private var dirty = false
    private var frameScheduled = false
    private var renderInFlight = false
    // 代际计数: 每次 requestRender 递增; 直接渲染完成回调按票据比对, 防吞新请求
    private var generation = 0L
    private var disposed = false

    fun requestRender() {
        if (disposed) return
        generation++
        dirty = true
        scheduleIfNeeded()
    }

    /**
     * 直接渲染（抬笔/重放/设为图标）入队前在主线程调用，取本次直接渲染的代际票据，
     * 完成后传给 [onDirectRenderCompleted]。
     */
    fun newDirectRenderToken(): Long = generation

    /**
     * 同批次已同步完成 scratchpadRender 后调用，消费掉待渲染标记。
     * 仅当代际未变（直接渲染入队后没有新 requestRender）才清 dirty:
     * 抬笔后立刻落笔的窗口里，晚于直接渲染入队的新请求不会被吞掉。
     */
    fun onDirectRenderCompleted(token: Long) {
        if (token == generation) {
            dirty = false
        }
    }

    /** 试画板销毁时调用: 挂起帧回调与在途 onDone 不再触发任何新调度（防 dispose 后空转） */
    fun dispose() {
        disposed = true
    }

    private fun scheduleIfNeeded() {
        if (disposed || !dirty || frameScheduled || renderInFlight) return
        frameScheduled = true
        postFrame {
            frameScheduled = false
            if (disposed || !dirty || renderInFlight) return@postFrame
            dirty = false
            renderInFlight = true
            enqueueRender {
                renderInFlight = false
                if (!disposed) scheduleIfNeeded()
            }
        }
    }
}

/**
 * Choreographer 帧回调包装：持有当前挂起回调的引用，试画板销毁时 [cancel] 移除，防泄漏。
 * post 会顶掉尚未来得及触发的上一帧挂起回调（同一 VSync 内只保留最新请求）。
 */
private class ScratchpadFrameDispatcher(private val choreographer: Choreographer) {
    private var pending: Choreographer.FrameCallback? = null

    fun post(block: () -> Unit) {
        cancel()
        val cb = Choreographer.FrameCallback { block() }
        pending = cb
        choreographer.postFrameCallback(cb)
    }

    fun cancel() {
        pending?.let { choreographer.removeFrameCallback(it) }
        pending = null
    }
}

/**
 * 试画台核心画布：直接呈现 C++ 引擎真实渲染的离线位图
 */
@Composable
internal fun ScratchpadCanvas(
    vm: PaintViewModel,
    scratchBitmap: Bitmap?,
    renderTick: Int,
    onStrokeStart: (ScratchPoint) -> Unit,
    onStrokeAddPoints: (List<ScratchPoint>) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 与主画布同源的手写笔输入：Compose 的 PointerInputChange 不暴露 tilt，
    // 这里用 pointerInteropFilter 拿原始 MotionEvent 采集倾斜与逐点历史压力。
    // pointerInteropFilter 回调收到的 MotionEvent 坐标已被平移到本 Composable 局部坐标系
    // （见 PointerInteropUtils.toMotionEventScope 的 offsetLocation），可直接作为 ScratchPoint 坐标。
    val state = remember { ScratchpadPointerState() }

    Canvas(
        modifier = modifier.pointerInteropFilter { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val idx = event.actionIndex
                    state.pointerId = event.getPointerId(idx)
                    val raw = scratchpadRawPressure(event.getPressure(idx), 1f)
                    state.initPressure = raw
                    state.lastX = event.getX(idx)
                    state.lastY = event.getY(idx)
                    vm.scratchpadLiveInput = raw
                    onStrokeStart(scratchpadPoint(vm, state, event, idx, raw))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = event.findPointerIndex(state.pointerId)
                    if (idx < 0) return@pointerInteropFilter state.pointerId >= 0
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    if (event.historySize == 0 && x == state.lastX && y == state.lastY) {
                        return@pointerInteropFilter true
                    }
                    val curRaw = scratchpadRawPressure(event.getPressure(idx), state.initPressure)
                    val batch = ArrayList<ScratchPoint>(event.historySize + 1)
                    for (h in 0 until event.historySize) {
                        // 历史点带自己的压力（>0 时），只有丢弃值（<=0）才回退当前点值
                        batch.add(
                            scratchpadHistoricalPoint(
                                vm,
                                state,
                                event,
                                idx,
                                h,
                                scratchpadRawPressure(event.getHistoricalAxisValue(MotionEvent.AXIS_PRESSURE, idx, h), curRaw),
                            ),
                        )
                    }
                    state.lastX = x
                    state.lastY = y
                    vm.scratchpadLiveInput = curRaw
                    batch.add(scratchpadPoint(vm, state, event, idx, curRaw))
                    onStrokeAddPoints(batch)
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == state.pointerId) {
                        state.reset()
                        vm.scratchpadLiveInput = -1f
                        vm.scratchpadLiveOutput = -1f
                        onStrokeEnd()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (state.pointerId >= 0) {
                        state.reset()
                        vm.scratchpadLiveInput = -1f
                        vm.scratchpadLiveOutput = -1f
                        onStrokeEnd()
                    }
                    true
                }
                else -> state.pointerId >= 0
            }
        },
    ) {
        // 读取 renderTick 触发重绘
        @Suppress("UNUSED_VARIABLE")
        val tick = renderTick

        if (scratchBitmap != null && !scratchBitmap.isRecycled) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(scratchBitmap, 0f, 0f, null)
            }
        }
    }
}

/**
 * 完整高内聚的试画板面板：内置离线 C++ 渲染调度、手势下发、撤销、清空与缩略图提取
 */
@Composable
internal fun BrushStudioScratchpadPanel(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    isCollapsible: Boolean = false,
    isExpanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
    onClose: () -> Unit = {},
    onImportThumbnail: () -> Unit = {},
) {
    val context = LocalContext.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scratchBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val strokes = remember { mutableStateListOf<List<ScratchPoint>>() }
    val currentStroke = remember { mutableListOf<ScratchPoint>() }
    var renderTick by remember { mutableIntStateOf(0) }
    var solidBg by remember { mutableStateOf(false) }
    var isDrawing by remember { mutableStateOf(false) }
    val smoothSession = remember { ScratchpadSmoothSession() }

    // 笔画中采样与像素读回解耦: Start/AddPoints 只入队采样 + requestRender(),
    // VSync 合帧后每帧至多一次 scratchpadRender (plan Task A)。
    val frameDispatcher = remember { ScratchpadFrameDispatcher(Choreographer.getInstance()) }
    val renderCoalescer = remember {
        ScratchpadRenderCoalescer(
            postFrame = frameDispatcher::post,
            enqueueRender = { onDone ->
                // VSync 合帧后统一读回: bmp 在主线程快照, JNI 在渲染线程执行
                val bmp = scratchBitmap
                vm.runCore(render = false, after = {
                    renderTick++
                    onDone()
                }) {
                    if (bmp != null && !bmp.isRecycled) {
                        ReverieCoreBridge.scratchpadRender(bmp)
                    }
                }
            },
        )
    }

    val thumbnailUpdatedToast = stringResource(R.string.brush_studio_toast_thumbnail_updated)

    DisposableEffect(Unit) {
        val color = vm.brushColor
        vm.runCore(render = false) {
            runCatching { ReverieCoreBridge.setBrushColor(color) }
        }
        onDispose {
            // 先撤掉挂起的 VSync 回调并停用合帧器, 防止销毁后仍触发读回/空转调度
            frameDispatcher.cancel()
            renderCoalescer.dispose()
            // runCore 只入队。bitmap 要等 scratchpadEnd 的 after 再回收，否则渲染线程会画到已回收的 bitmap。
            val bmp = scratchBitmap
            scratchBitmap = null
            vm.runCore(render = false, after = { bmp?.recycle() }) {
                ReverieCoreBridge.scratchpadEnd()
            }
        }
    }

    // 重放已有笔画至 C++ 试画板。strokes 在主线程快照；JNI 在渲染线程。
    // 直通录制时的真实物理压感与倾角，彻底清退人造假斜坡与假倾角伪算法。
    fun replayStrokesOnEngine(bmp: Bitmap) {
        val snapshot = strokes.map { it.toList() }
        val directToken = renderCoalescer.newDirectRenderToken()
        vm.runCore(render = false, after = {
            renderTick++
            // 重放批次末尾已同步渲染; 按代际票据消费待渲染标记, 不吞入队后新请求
            renderCoalescer.onDirectRenderCompleted(directToken)
        }) {
            ReverieCoreBridge.scratchpadClear()
            for (stroke in snapshot) {
                if (stroke.isEmpty()) continue
                val n = stroke.size
                for (i in 0 until n) {
                    val p = stroke[i]
                    if (i == 0) {
                        ReverieCoreBridge.scratchpadStrokeStart(
                            p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(),
                            p.tiltX.toDouble(), p.tiltY.toDouble(), p.rotation.toDouble(),
                        )
                    } else {
                        ReverieCoreBridge.scratchpadStrokeMove(
                            p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(),
                            p.tiltX.toDouble(), p.tiltY.toDouble(), p.rotation.toDouble(),
                        )
                    }
                }
                ReverieCoreBridge.scratchpadStrokeEnd()
            }
            if (!bmp.isRecycled) {
                bmp.eraseColor(0)
                ReverieCoreBridge.scratchpadRender(bmp)
            }
        }
    }

    // 5B: REBUILD 类滑块防抖落盘重载（BrushStoreVm.flush → reload → notifyBrushModified）后，
    // 自动把已有笔画用新参数全量重放（先 clear 再逐笔，幂等）。
    // lastHandledEpoch 初始 -1 哨兵：首次组合不触发（进入面板时 epoch 可能已非 0）；
    // isDrawing 为 true 时先不标记，待其变 false 后本 effect 重跑补放，避免重放与正在画的笔画交错。
    var lastHandledEpoch by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(vm.brushModifiedEpoch, isDrawing) {
        val epoch = vm.brushModifiedEpoch
        if (lastHandledEpoch == -1L) {
            lastHandledEpoch = epoch
            return@LaunchedEffect
        }
        if (epoch == lastHandledEpoch || isDrawing) return@LaunchedEffect
        lastHandledEpoch = epoch
        val bmp = scratchBitmap
        if (strokes.isNotEmpty() && bmp != null && !bmp.isRecycled) {
            replayStrokesOnEngine(bmp)
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .border(0.6.dp, Morandi.border.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        // ---- 顶部控制栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.brush_studio_scratchpad_title),
                    color = Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.brush_studio_scratchpad_sub),
                    color = Morandi.subText,
                    fontSize = 10.sp,
                )
            }

            // 撤销动作
            ReIconButton(
                R.drawable.ic_undo,
                stringResource(R.string.brush_studio_scratchpad_undo),
                onTap = {
                    if (strokes.isNotEmpty()) {
                        strokes.removeAt(strokes.lastIndex)
                        val bmp = scratchBitmap
                        if (bmp != null && !bmp.isRecycled) {
                            replayStrokesOnEngine(bmp)
                        }
                    }
                },
                tint = if (strokes.isNotEmpty()) Morandi.text else Morandi.subText.copy(alpha = 0.35f),
                iconSize = 15.dp,
                size = 30.dp,
            )

            // 清空动作
            ReIconButton(
                R.drawable.ic_trash,
                stringResource(R.string.brush_studio_scratchpad_clear),
                onTap = {
                    strokes.clear()
                    currentStroke.clear()
                    val bmp = scratchBitmap
                    vm.runCore(render = false, after = { renderTick++ }) {
                        ReverieCoreBridge.scratchpadClear()
                        if (bmp != null && !bmp.isRecycled) {
                            bmp.eraseColor(0)
                        }
                    }
                },
                tint = if (strokes.isNotEmpty()) Morandi.text else Morandi.subText.copy(alpha = 0.35f),
                iconSize = 15.dp,
                size = 30.dp,
            )

            // 背景棋盘格 / 纯色切换
            ReIconButton(
                if (solidBg) R.drawable.ic_layers else R.drawable.ic_circle,
                stringResource(if (solidBg) R.string.scratchpad_bg_checker else R.string.scratchpad_bg_solid),
                onTap = { solidBg = !solidBg },
                tint = Morandi.subText,
                iconSize = 15.dp,
                size = 30.dp,
            )

            // 设为预设图标。笔画在主线程快照；像素在渲染线程 scratchpadRender 返回后拷贝，JNI 此时已解锁。
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panel.copy(alpha = 0.85f))
                    .clickable {
                        val strokeSnapshot = strokes.map { it.toList() }
                        val bmp = scratchBitmap
                        val directToken = renderCoalescer.newDirectRenderToken()
                        var pixelCopy: Bitmap? = null
                        vm.runCore(render = false, after = {
                            // 同批次末尾已同步渲染; 按代际票据消费待渲染标记, 不吞入队后新请求
                            renderCoalescer.onDirectRenderCompleted(directToken)
                            captureScratchpadAsThumbnail(context, vm, pixelCopy, strokeSnapshot)
                            pixelCopy?.recycle()
                            Toast.makeText(context, thumbnailUpdatedToast, Toast.LENGTH_SHORT).show()
                        }) {
                            if (bmp != null && !bmp.isRecycled) {
                                ReverieCoreBridge.scratchpadRender(bmp)
                                pixelCopy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_pencil),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        stringResource(R.string.brush_studio_scratchpad_set_icon),
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // 导入自定义图标
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panel.copy(alpha = 0.85f))
                    .clickable { onImportThumbnail() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        tint = Morandi.text,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        stringResource(R.string.brush_studio_import_thumbnail),
                        color = Morandi.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (isCollapsible) {
                Spacer(Modifier.width(4.dp))
                ReIconButton(
                    R.drawable.ic_x,
                    stringResource(R.string.common_close),
                    onTap = onClose,
                    tint = Morandi.subText,
                    iconSize = 15.dp,
                    size = 30.dp,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ---- 画布区域 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0 &&
                        (size.width != canvasSize.width || size.height != canvasSize.height)
                    ) {
                        canvasSize = size
                        val oldBmp = scratchBitmap
                        val newBmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        scratchBitmap = newBmp
                        vm.runCore(render = false, after = { oldBmp?.recycle() }) {
                            ReverieCoreBridge.scratchpadStart(size.width, size.height)
                        }
                        replayStrokesOnEngine(newBmp)
                    }
                },
        ) {
            if (!solidBg) {
                CheckerboardBackground(
                    modifier = Modifier.fillMaxSize(),
                    color1 = Color(0xFFFFFFFF),
                    color2 = Color(0xFFE8E8EC),
                    gridSize = 12.dp,
                )
            }

            ScratchpadCanvas(
                vm = vm,
                scratchBitmap = scratchBitmap,
                renderTick = renderTick,
                onStrokeStart = { p ->
                    isDrawing = true
                    smoothSession.start(p.x, p.y)
                    currentStroke.clear()
                    currentStroke.add(p)
                    val color = vm.brushColor
                    vm.runCore(render = false) {
                        runCatching { ReverieCoreBridge.setBrushColor(color) }
                        ReverieCoreBridge.scratchpadStrokeStart(
                            p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(),
                            p.tiltX.toDouble(), p.tiltY.toDouble(), p.rotation.toDouble(),
                        )
                    }
                    // 笔画中不直调 scratchpadRender: 入队 VSync 合帧读回 (同帧多批只渲一次)
                    renderCoalescer.requestRender()
                },
                onStrokeAddPoints = { pts ->
                    val s = vm.brushStreamline
                    // 流线关闭（s<=0.001）：完全直通，采样路径与原实现逐字节一致
                    val ptsCopy: List<ScratchPoint> = if (s <= 0.001) {
                        pts.toList()
                    } else {
                        // 每个采样点（含 history 批内各点）先经指数平滑得到 effX/effY 再入笔画与引擎
                        val out = ArrayList<ScratchPoint>(pts.size)
                        for (p in pts) {
                            val (ex, ey) = scratchpadStreamline(s, smoothSession.sx, smoothSession.sy, p.x, p.y)
                            smoothSession.sx = ex
                            smoothSession.sy = ey
                            smoothSession.lastRaw = p
                            out.add(ScratchPoint(ex.toFloat(), ey.toFloat(), p.pressure, p.tiltX, p.tiltY, p.rotation, p.timeMs))
                        }
                        out
                    }
                    currentStroke.addAll(ptsCopy)
                    vm.runCore(render = false) {
                        for (p in ptsCopy) {
                            ReverieCoreBridge.scratchpadStrokeMove(
                                p.x.toDouble(), p.y.toDouble(), p.pressure.toDouble(),
                                p.tiltX.toDouble(), p.tiltY.toDouble(), p.rotation.toDouble(),
                            )
                        }
                    }
                    // 笔画中不直调 scratchpadRender: 入队 VSync 合帧读回 (同帧多批只渲一次)
                    renderCoalescer.requestRender()
                },
                onStrokeEnd = {
                    isDrawing = false
                    // 收尾：把平滑点直接推到最终目标点（补 1 个点）。
                    // 不做主画布的分段 catch-up——试画板是预览，单点收尾足够（取舍见汇报）。
                    val tailPt = if (vm.brushStreamline > 0.001) {
                        val raw = smoothSession.lastRaw
                        // 0.5px 收尾补点去抖阈值：最终点与平滑点距离不足半像素时不补点，避免亚像素抖动触发补点
                        if (raw != null &&
                            Math.hypot(
                                (raw.x - smoothSession.sx).toDouble(), (raw.y - smoothSession.sy).toDouble(),
                            ) > 0.5
                        ) {
                            // R4-B: 收尾补点压感与主画布同口径 — 抬笔真实末端压感, 下限 0.05
                            val tailP = maxOf(
                                raw.pressure,
                                SCRATCHPAD_TAIL_PRESSURE_FLOOR,
                            )
                            ScratchPoint(raw.x, raw.y, tailP, raw.tiltX, raw.tiltY, raw.rotation, raw.timeMs)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    smoothSession.reset()
                    if (tailPt != null) {
                        currentStroke.add(tailPt)
                    }
                    if (currentStroke.isNotEmpty()) {
                        strokes.add(currentStroke.toList())
                        currentStroke.clear()
                    }
                    val bmp = scratchBitmap
                    val directToken = renderCoalescer.newDirectRenderToken()
                    vm.runCore(render = false, after = {
                        renderTick++
                        // 抬笔批次末尾已同步渲染; 按代际票据消费待渲染标记,
                        // 抬笔后立刻落笔的新 requestRender (代际已变) 不会被吞
                        renderCoalescer.onDirectRenderCompleted(directToken)
                    }) {
                        if (tailPt != null) {
                            ReverieCoreBridge.scratchpadStrokeMove(
                                tailPt.x.toDouble(), tailPt.y.toDouble(), tailPt.pressure.toDouble(),
                                tailPt.tiltX.toDouble(), tailPt.tiltY.toDouble(), tailPt.rotation.toDouble(),
                            )
                        }
                        ReverieCoreBridge.scratchpadStrokeEnd()
                        if (bmp != null && !bmp.isRecycled) {
                            ReverieCoreBridge.scratchpadRender(bmp)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 空白提示文字
            if (strokes.isEmpty() && currentStroke.isEmpty()) {
                Text(
                    stringResource(R.string.brush_studio_scratchpad_hint),
                    color = Color(0x66000000),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

// ==========================================
// Dialogs
// ==========================================

@Composable
internal fun StudioNewBrushDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    val defaultGroup = stringResource(R.string.brush_preset_custom_tag)
    var name by remember { mutableStateOf("") }
    var group by remember(defaultGroup) { mutableStateOf(defaultGroup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_new_dialog_title), color = textMain, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.brush_studio_new_dialog_hint), color = textSub, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = textMain, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_create),
                onClick = { onCreate(name.trim(), group) },
                enabled = name.isNotBlank(),
                textColor = if (name.isNotBlank()) textMain else textSub,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = textSub)
        },
        containerColor = cardBg,
    )
}

@Composable
internal fun StudioRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
    borderCol: Color,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_rename_dialog_title), color = textMain, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.brush_studio_rename_dialog_hint), color = textSub, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = textMain, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_save),
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
                textColor = if (name.isNotBlank()) textMain else textSub,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = textSub)
        },
        containerColor = cardBg,
    )
}

internal const val TIP_THUMB_MAX = 192

@Composable
internal fun CheckerboardBackground(
    modifier: Modifier = Modifier,
    color1: Color = Morandi.panel,
    color2: Color = Morandi.panelHi,
    gridSize: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Canvas(modifier = modifier) {
        val checkSize = gridSize.toPx()
        val cols = (size.width / checkSize).toInt() + 1
        val rows = (size.height / checkSize).toInt() + 1
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val c = if ((i + j) % 2 == 0) color1 else color2
                drawRect(
                    color = c,
                    topLeft = Offset(i * checkSize, j * checkSize),
                    size = Size(checkSize, checkSize),
                )
            }
        }
    }
}
