/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.*
import android.graphics.PorterDuff
import com.reverie.paint.perf.PerfHud
import kotlin.math.hypot
import com.reverie.paint.ui.painting.canvas.CanvasTouchView.LargeBitmapTile

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.applyHighRefreshRateAndUnbuffered() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            requestUnbufferedDispatch(android.view.InputDevice.SOURCE_STYLUS)
            requestUnbufferedDispatch(android.view.InputDevice.SOURCE_TOUCHSCREEN)
        } catch (_: Throwable) {}
    }
    val maxFps = if (Build.VERSION.SDK_INT >= 30) {
        val d = try { display } catch (_: Throwable) { null }
        d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 144f
    } else 144f
    universalPredictor.setRefreshRate(maxFps)
    // B4: 交互期请求面板最高帧率; 静止 1.5s 后由 [downclockWhenIdle] 降回 60Hz
    frameRateHigh = true
    requestMaxFrameRate()
}

/** B4: 请求"面板最高帧率"(交互期用)。拿不到就静默放弃, 不影响绘制。 */
internal fun CanvasTouchView.requestMaxFrameRate() {
    if (Build.VERSION.SDK_INT < 34) return
    val maxFps = try {
        display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 144f
    } catch (_: Throwable) {
        144f
    }
    applyFrameRateHint(maxFps)
}

/** B4: 通过 `View.setFrameRate`(API 34+) 给系统一个帧率提示(反射调用, 失败即忽略)。 */
internal fun CanvasTouchView.applyFrameRateHint(fps: Float) {
    if (Build.VERSION.SDK_INT < 34) return
    try {
        val method = View::class.java.getMethod("setFrameRate", java.lang.Float.TYPE, java.lang.Integer.TYPE)
        method.invoke(this, fps, 1) // 1 = Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
    } catch (_: Throwable) {}
}

/**
 * B4 · 静止降频: 一有真实输入就把帧率请求提到最高, 并重置 1.5s 的静止计时; 计时到点若确实
 * 没有触摸/悬停/动画, 就把请求降回 60Hz —— 只是"别让看画把屏幕钉在 144Hz 上", 不影响任何绘制。
 */
internal fun CanvasTouchView.markInteractionFrameRate() {
    if (!frameRateHigh) {
        frameRateHigh = true
        requestMaxFrameRate()
    }
    removeCallbacks(idleDownclockRunnable)
    postDelayed(idleDownclockRunnable, IDLE_DOWNCLOCK_MS)
}

internal fun CanvasTouchView.downclockWhenIdle() {
    if (!frameRateHigh) return
    if (localIsTouching || localIsHovering || isInteracting) return
    val v = vm
    if (v != null && v.anim.isPlaying) return
    frameRateHigh = false
    applyFrameRateHint(IDLE_FRAME_RATE_HZ)
}

internal fun CanvasTouchView.checkAndRestoreHighRefreshRate() {
    val now = SystemClock.uptimeMillis()
    if (now - lastRefreshRateCheckTime < 3000L) return
    lastRefreshRateCheckTime = now
    val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try { display } catch (_: Throwable) { null }
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    }
    val modes = d?.supportedModes ?: return
    val maxFps = modes.maxOfOrNull { it.refreshRate } ?: return
    val curFps = d.refreshRate
    if (maxFps > 60f && curFps < maxFps - 5f) {
        (context as? android.app.Activity)?.let { act ->
            com.reverie.paint.MainActivity.applyHighRefreshRate(act)
        }
        applyHighRefreshRateAndUnbuffered()
    }
}

internal fun CanvasTouchView.drawLargeBitmapTiled(
    canvas: Canvas,
    bmp: Bitmap,
    left: Float,
    top: Float,
    paint: Paint,
) {
    val tileDim = LargeBitmapTilingPolicy.TILE_DIM
    val (cols, rows) = LargeBitmapTilingPolicy.tileCount(bmp.width, bmp.height, tileDim)
    val totalTiles = cols * rows

    val tilesNeedRealloc = largeBitmapTiles == null ||
        largeBitmapTiles?.size != totalTiles ||
        largeBitmapCols != cols ||
        largeBitmapRows != rows

    if (tilesNeedRealloc) {
        largeBitmapTiles?.forEach { it.bitmap.recycle() }
        try {
            val newTiles = Array(totalTiles) { idx ->
                val c = idx % cols
                val r = idx / cols
                val sx = c * tileDim
                val sy = r * tileDim
                val tw = minOf(tileDim, bmp.width - sx)
                val th = minOf(tileDim, bmp.height - sy)
                val tileBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                LargeBitmapTile(
                    bitmap = tileBmp,
                    canvas = Canvas(tileBmp),
                    sx = sx,
                    sy = sy,
                    width = tw,
                    height = th,
                )
            }
            largeBitmapTiles = newTiles
            largeBitmapCols = cols
            largeBitmapRows = rows
            largeBitmapLastGenId = -1
        } catch (t: Throwable) {
            android.util.Log.e("CanvasTouchView", "Failed to allocate large bitmap tiles", t)
            largeBitmapTiles?.forEach { it.bitmap.recycle() }
            largeBitmapTiles = null
            return
        }
    }

    val tiles = largeBitmapTiles ?: return
    if (largeBitmapLastGenId != bmp.generationId) {
        val v = vm
        val hasAccum = v?.consumeLargeBitmapDirtyRect(largeBitmapDirtyScratch) == true
        val isFullUpdate = largeBitmapLastGenId == -1 || !hasAccum ||
            (largeBitmapDirtyScratch.width() >= bmp.width && largeBitmapDirtyScratch.height() >= bmp.height)

        if (isFullUpdate) {
            for (tile in tiles) {
                largeBitmapSrcRect.set(tile.sx, tile.sy, tile.sx + tile.width, tile.sy + tile.height)
                largeBitmapDstRect.set(0, 0, tile.width, tile.height)
                tile.canvas.drawBitmap(bmp, largeBitmapSrcRect, largeBitmapDstRect, largeBitmapTilePaint)
            }
        } else {
            val dirtyL = largeBitmapDirtyScratch.left
            val dirtyT = largeBitmapDirtyScratch.top
            val dirtyR = largeBitmapDirtyScratch.right
            val dirtyB = largeBitmapDirtyScratch.bottom
            for (tile in tiles) {
                val interL = maxOf(tile.sx, dirtyL)
                val interT = maxOf(tile.sy, dirtyT)
                val interR = minOf(tile.sx + tile.width, dirtyR)
                val interB = minOf(tile.sy + tile.height, dirtyB)
                if (interR > interL && interB > interT) {
                    largeBitmapSrcRect.set(interL, interT, interR, interB)
                    largeBitmapDstRect.set(interL - tile.sx, interT - tile.sy, interR - tile.sx, interB - tile.sy)
                    tile.canvas.drawBitmap(bmp, largeBitmapSrcRect, largeBitmapDstRect, largeBitmapTilePaint)
                }
            }
        }
        largeBitmapLastGenId = bmp.generationId
    }

    // 视口裁剪: 仅绘制当前视口内可见的瓦片, 彻底杜绝超大画幅高倍缩放下的 GPU 纹理抖动与带宽吞吐过载
    ensureViewTransform()
    visibleBitmapBounds(boundsScratch)
    val visL = boundsScratch[0]
    val visT = boundsScratch[1]
    val visR = boundsScratch[2]
    val visB = boundsScratch[3]

    for (tile in tiles) {
        if (tile.sx + tile.width >= visL && tile.sx <= visR &&
            tile.sy + tile.height >= visT && tile.sy <= visB) {
            val tx = left + tile.sx
            val ty = top + tile.sy
            canvas.drawBitmap(tile.bitmap, tx, ty, paint)
        }
    }
}

internal fun CanvasTouchView.releaseLargeBitmapTiles() {
    largeBitmapTiles?.forEach { it.bitmap.recycle() }
    largeBitmapTiles = null
    largeBitmapLastGenId = -1
}

/**
 * 按视口裁剪逐瓦片画画布。变换由调用方 (drawCanvas) 设好 —— 这里发
 * `tile @ (tileLeft - halfW, tileTop - halfH)`, 与整张 `drawBitmap(bmp, -imgW/2, -imgH/2)`
 * 逐像素等价 (瓦片文档对齐且连续)。
 *
 * 视口外的瓦片连绘制命令都不发: 没被画过的位图不会被 HWUI 建纹理, 真正滚进视口时才上传;
 * 已上传又没被重写的瓦片则直接命中纹理缓存。
 */
internal fun CanvasTouchView.drawDisplayTiles(canvas: Canvas, tiles: com.reverie.paint.core.PublishedTiles) {
    val grid = tiles.grid
    ensureViewTransform()
    visibleBitmapBounds(boundsScratch)
    if (tileDrawScratch.size < 2 * grid.tileCount) {
        tileDrawScratch = IntArray(2 * grid.tileCount)
    }
    val n =
        grid.tilesIntersecting(
            boundsScratch[0],
            boundsScratch[1],
            boundsScratch[2],
            boundsScratch[3],
            tileDrawScratch,
        )
    if (n <= 0) return
    val halfW = grid.width / 2f
    val halfH = grid.height / 2f
    for (k in 0 until n) {
        val tx = tileDrawScratch[k * 2]
        val ty = tileDrawScratch[k * 2 + 1]
        val idx = grid.tileIndex(tx, ty)
        val tile = tiles.tiles.get(idx) ?: continue
        if (tile.isRecycled) continue
        // 交给 HWUI 之前登记: 之后渲染线程不再就地写这张位图 (见 PublishedTiles.drawnByView)。
        tiles.markDrawn(idx)
        // 过滤开着时不能直接 drawBitmap: 边上会采到瓦片外的透明, 棋盘格透成网格。
        clampedTiles.draw(
            canvas,
            idx,
            grid.tileCount,
            tile,
            grid.tileLeft(tx) - halfW,
            grid.tileTop(ty) - halfH,
            directBitmapPaint.isFilterBitmap,
        )
    }
}

internal fun CanvasTouchView.drawCanvas(canvas: Canvas) {
    superOnDraw(canvas)
    val v = vm ?: return

    // =========================================================================
    // 1. 硬件加速直出 Krita 渲染画布 (消除 Compose 重组调度延迟)
    // 统一单点呈现：阴影、透明棋盘格、位图与像素网格硬件加速直出
    // =========================================================================
    // 1:1 路径下瓦片网格是画布的唯一来源; 整图 displayBitmap 只在整图路径 (缩放回退/
    // 动画播放/超大画布) 或网格尚未建立时才画 —— 两者绝不同帧一起画。
    // 换文档期间 (网格已作废、新文档首帧还没上屏) 两个来源都**不许**画: 那时候手里的
    // 整图位图装的是上一份文档, 画出来就是"打开工程时一直显示上一个文件"。
    // 先读修订号再读瓦片引用: drawnRev 只会偏小不会偏大, 避免误把尚未绘制的更新版本判定为已追平。
    val drawnRev = v.tileRevision
    val tiles = v.displayTiles.drawable()
    val bmp =
        if (tiles != null || v.documentSwitchPending) {
            null
        } else {
            (v.displayBitmap ?: docBitmap)?.takeIf { !it.isRecycled }
        }
    val sceneW = tiles?.grid?.width ?: (bmp?.width ?: 0)
    val sceneH = tiles?.grid?.height ?: (bmp?.height ?: 0)
    if (sceneW > 0 && sceneH > 0) {
        val imgW = sceneW.toFloat()
        val imgH = sceneH.toFloat()
        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val centerX = viewW / 2f + canvasPanX
        val centerY = viewH / 2f + canvasPanY

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(canvasRotation)
        canvas.scale(scale, scale)
        // 视图翻转: 在当前原点(=画布中心)上做轴镜像, 与 CanvasViewTransform 的
        // signX/signY 完全同源 —— 位图、棋盘格、像素网格一起镜像, 而缩放/旋转
        // 手势的语义不变 (镜像只作用于显示, 不改任何一层的像素)。
        if (canvasFlipX || canvasFlipY) {
            canvas.scale(
                if (canvasFlipX) -1f else 1f,
                if (canvasFlipY) -1f else 1f,
            )
        }

        // 绘制真实画布像素
        directBitmapPaint.isFilterBitmap = v.magnificationInterpolation
        directBitmapPaint.isAntiAlias = v.magnificationInterpolation
        realInk.interpolate = v.magnificationInterpolation
        if (tiles != null) {
            if (largeBitmapTiles != null) {
                releaseLargeBitmapTiles()
            }
            drawDisplayTiles(canvas, tiles)
            if (LandDiag.enabled && LandDiag.armed) {
                LandDiag.holdDraw(true, v.displayTiles.generation, drawnRev, isInteracting)
            }
            // 液化上屏用的整图快照 (只在液化等待上屏/参考窗口开着时才存在)
            v.displayBitmap?.let { v.onLiquifyBitmapDrawn(it) }
        } else if (bmp != null) {
            clampedTiles.clear()
            if (LargeBitmapTilingPolicy.isLargeBitmap(bmp.byteCount.toLong())) {
                drawLargeBitmapTiled(canvas, bmp, -imgW / 2f, -imgH / 2f, directBitmapPaint)
            } else {
                if (largeBitmapTiles != null) {
                    releaseLargeBitmapTiles()
                }
                canvas.drawBitmap(bmp, -imgW / 2f, -imgH / 2f, directBitmapPaint)
            }
            v.onLiquifyBitmapDrawn(bmp)
        }
        val cur3DLayer = v.layers.getOrNull(v.currentLayerIndex)
        if (v.active3DLayers.isNotEmpty()) {
            for (layer in v.layers) {
                if (!layer.visible) continue
                val state = v.active3DLayers[layer.id] ?: continue
                // 逐帧路径复用顶点缓冲; 形变结果自带骨架与关节世界坐标,
                // 骨架覆盖层永远画在当前图层自己的骨架上 (旧实现命中缓存时不刷新 → 错位)
                val deformed = v.getInteractiveDeformed(state)
                canvas3DDrawer.draw(
                    canvas,
                    deformed.mesh,
                    state,
                    imgW.toFloat(),
                    imgH.toFloat(),
                    layer.opacity.toFloat(),
                )
                if (layer.id == cur3DLayer?.id && v.isCurrentLayer3D && v.active3DInteractionMode == Interaction3DMode.BONE) {
                    canvas3DDrawer.drawBones(
                        canvas,
                        deformed.skeleton,
                        deformed.worldJoints,
                        v.active3DSelectedJointName,
                        imgW.toFloat(),
                        imgH.toFloat(),
                        state,
                        v.active3DBoneAxis,
                    )
                }
            }
        }

        // 像素级网格高倍率缩放展示 (scale >= 4.0)
        drawPixelGrid(canvas, v, scale, sceneW, sceneH)

        canvas.restore()
        // 画布已经画出这一帧的瓦片: 抬笔后的真墨追赶到此为止, 这一帧提交后清前缓冲。
        realInk.clearIfViewCaughtUp(drawnRev)
        // H-1: 装饰几何变了就 (防抖) 重建孔洞掩码 —— 只做引用/数值比较, 构建在后台线程。
        // 按住运笔时窗口不重画, 这里也不会跑。
        if (!realInk.held && !localIsTouching && FrontBufferFeatureFlags.holeMode != FrontBufferHolePolicy.MODE_OFF) {
            // 视图动画 (适配复位 / 旋转吸附) 还在跑时按连续变化防抖; 否则静止后第一帧立即建。
            val settled = fitAnimator?.isRunning != true && snapAnimator?.isRunning != true
            realInk.pose?.let { pose ->
                if (composeApplies(v, pose)) {
                    // compose 不用掩码: 静止后把静态覆盖层录好, 落笔时直接用。
                    if (settled) composeDecorFor(v, pose)
                } else {
                    holeMasks.scheduleIfStale(v, pose, settled)
                }
            }
        }
    }

    // Phase 2B: AGSL 形变预览覆盖层。引擎在"主机侧绘制"模式下不生成也不叠加 CPU 预览,
    // 由这里在显示分辨率上做位移采样 —— 因此缩放/旋转/平移都自动跟随, 且不用改画布位图。
    // 开关关闭时 active 恒为 false, 这段在正式使用中不会执行。
    // VSYNC 绑定: 一帧内可能"只暂存了状态、还没提交", 因此以 requested 为门,
    // 让 draw() 自己在帧内完成"提交(=纹理上传) + 绘制"。
    if (LiquifyGpuPreview.requested || LiquifyGpuPreview.active) {
        ensureViewTransform()
        if (LiquifyGlesPreview.requested) {
            // Phase 5 · C2: 本次手势由 GLES 覆盖层画 —— 这里**不画 AGSL、也不上传纹理**,
            // 只把"文档 → 屏幕"的仿射喂给它(源裁剪/位移网格由引擎线程喂, 见
            // PaintViewModel.pollLiquifyGpuPreview), 出图在它自己的渲染线程上。
            // 两者同时打开时 GLES 优先, 免得同一帧被画两遍。
            LiquifyGlesPreview.pushAffine(viewTransform)
        } else {
            // Phase 3 · Commit 1b 埋点: 覆盖层"提交(纹理构建/上传) + 绘制"在 UI 线程的实际耗时。
            // draw p95 只含绘制命令录制、不含纹理上传与 GPU, 所以要用这一项才能判断覆盖层贵不贵。
            val lqOverlayT0 = System.nanoTime()
            LiquifyGpuPreview.draw(canvas, viewTransform)
            PerfTrace.liquifyOverlay(System.nanoTime() - lqOverlayT0)
        }
    }

    // 标尺的液化网格可视化(debug 专属; release 侧 PerfHud 为恒 false 的空实现, 不进这个分支):
    // 把 Krita 网格的"原始点 → 位移后点"画成箭头, 用于在实现 Preview 之前确认
    // 网格几何、位移方向与文档→屏幕映射与最终结果一致。
    if (PerfHud.gridOverlayEnabled) {
        ensureViewTransform()
        PerfHud.drawLiquifyGrid(canvas, viewTransform)
    }

    // =========================================================================
    // 1.5 笔尖前向超前预测 (双系统分派)
    // - 前缓冲预测开启: 前沿孪生预览 [P_frontier -> P_cur -> P_pred], 前缓冲直出,
    //   不可用时在此软件回退绘制 (回退空洞见 FrontBufferProbe.softwareFallbackRequired)
    // - 前缓冲预测关闭: 上游原版 OEM 硬件预测渐隐尾线 (仅 OPPO 硬件预测器有效)
    // =========================================================================
    val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
    if (!v.frontBufferPredictionEnabled && v.stylusStrokePredictionEnabled && v.isCurrentBrushPredictionEligible) {
        // 上游原版: OEM 硬件预测尾线 (实时预测未来 15~20ms 笔尖切线, 微羽化渐隐)
        val isDrawingTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER
        if (localIsTouching && isDrawingTool && hasPredictedScreenPoint && hasLocalCursorPos) {
            try {
                val dx = predictedScreenX - localCursorX
                val dy = predictedScreenY - localCursorY
                val dist = hypot(dx, dy)
                val maxDistPx = 14f * density
                val minDistPx = 2.5f * density
                if (dist in minDistPx..(maxDistPx * 3.5f) && predictedPressure > 0.05f) {
                    val prevPos = previousSinglePos
                    var angleOk = true
                    if (prevPos != Offset.Zero) {
                        val v1x = localCursorX - prevPos.x
                        val v1y = localCursorY - prevPos.y
                        val len1 = hypot(v1x, v1y)
                        if (len1 > 1.5f) {
                            val dot = (v1x * dx + v1y * dy) / (len1 * dist)
                            if (dot < 0.55f) { // 急转弯或大幅变向时抑制直线外推
                                angleOk = false
                            }
                        }
                    }

                    if (angleOk) {
                        val clampDist = dist.coerceAtMost(maxDistPx)
                        val endX = localCursorX + (dx / dist) * clampDist
                        val endY = localCursorY + (dy / dist) * clampDist

                        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                        val cursorBrushSize = when {
                            effTool() == Tool.LIQUIFY -> liquifyBrushSize
                            effTool() == Tool.ERASER && tool != Tool.ERASER -> v.getToolEffectiveSize("eraser").toFloat()
                            else -> v.brushSize.toFloat()
                        }
                        val pFrac = if (v.brushPressureEnabled) pressureFractionCached(predictedPressure) else 1f
                        val strokeWidth = (cursorBrushSize * scale * pFrac).coerceAtLeast(1.5f)

                        val isEraser = effTool() == Tool.ERASER
                        val baseColor = if (isEraser) {
                            android.graphics.Color.WHITE
                        } else {
                            resolveBrushColorCached(v.brushColor)
                        }
                        val baseAlpha = (if (isEraser) 0.8 else (v.brushOpacity * (if (v.brushFlow > 0.0) v.brushFlow else 1.0))).coerceIn(0.05, 1.0).toFloat()

                        val startColor = android.graphics.Color.argb(
                            (baseAlpha * 0.55f * 255).toInt().coerceIn(0, 255),
                            android.graphics.Color.red(baseColor),
                            android.graphics.Color.green(baseColor),
                            android.graphics.Color.blue(baseColor)
                        )
                        val endColor = android.graphics.Color.argb(
                            0, // 终点彻底渐隐至 0% 透明度，彻底消除圆形粗钝 Cap 假线感
                            android.graphics.Color.red(baseColor),
                            android.graphics.Color.green(baseColor),
                            android.graphics.Color.blue(baseColor)
                        )
                        tipShaderPaint.strokeWidth = strokeWidth
                        tipShaderPaint.shader = android.graphics.LinearGradient(
                            localCursorX, localCursorY, endX, endY,
                            startColor, endColor,
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        canvas.drawLine(localCursorX, localCursorY, endX, endY, tipShaderPaint)

                        // 对称模式: 将硬件预测尾线同步镜像绘制到所有对称分支
                        if (hasSymmetry) {
                            ensureViewTransform()
                            viewTransform.screenToDoc(localCursorX, localCursorY, pointScratch)
                            val countStart = v.drawingGuide.writeSymmetricPoints(
                                pointScratch[0], pointScratch[1],
                                v.docWidth, v.docHeight,
                                symScratchX, symScratchY
                            )
                            viewTransform.screenToDoc(endX, endY, pointScratch)
                            val countEnd = v.drawingGuide.writeSymmetricPoints(
                                pointScratch[0], pointScratch[1],
                                v.docWidth, v.docHeight,
                                symEndScratchX, symEndScratchY
                            )
                            val symCount = minOf(countStart, countEnd)
                            for (i in 0 until symCount) {
                                viewTransform.docToScreen(symScratchX[i], symScratchY[i], pointScratch)
                                val startSx = pointScratch[0]
                                val startSy = pointScratch[1]
                                viewTransform.docToScreen(symEndScratchX[i], symEndScratchY[i], pointScratch)
                                val endSx = pointScratch[0]
                                val endSy = pointScratch[1]
                                tipShaderPaint.shader = android.graphics.LinearGradient(
                                    startSx, startSy, endSx, endSy,
                                    startColor, endColor,
                                    android.graphics.Shader.TileMode.CLAMP
                                )
                                canvas.drawLine(startSx, startSy, endSx, endSy, tipShaderPaint)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // 2. 绘画中实时镜像笔迹绘制 (120Hz 零延迟 GPU Canvas 渲染)
    // =========================================================================
    if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing && (localIsTouching || isSymmetryReplaying) && mirrorBranchHasSamples()) {
        try {
            ensureViewTransform()
            updateSymmetryPressureLut(v)
            // 工具/颜色口径取上游 1.3.3: 橡皮与涂抹用固定灰, 其余按笔刷色 × 不透明度
            when (effTool()) {
                Tool.ERASER -> {
                    mirroredDrawPaint.color = android.graphics.Color.argb(160, 240, 240, 245)
                }
                Tool.SMUDGE -> {
                    mirroredDrawPaint.color = android.graphics.Color.argb(120, 180, 180, 190)
                }
                else -> {
                    val baseColor = resolveBrushColorCached(v.brushColor)
                    val flowFactor = if (v.brushFlow > 0.0) v.brushFlow.coerceIn(0.05, 1.0) else 1.0
                    val alpha = (v.brushOpacity.coerceIn(0.0, 1.0) * flowFactor * 255.0).toInt().coerceIn(1, 255)
                    mirroredDrawPaint.color = (baseColor and 0x00FFFFFF) or (alpha shl 24)
                }
            }
            mirroredPointPaint.color = mirroredDrawPaint.color
            // 宽口径取对应工具的真实有效尺寸与压感查找表，数据仍走零分配扁平缓冲
            val effBrushSize = when {
                effTool() == Tool.ERASER && tool != Tool.ERASER -> v.getToolEffectiveSize("eraser").toFloat()
                else -> v.brushSize.toFloat()
            }
            val baseStrokeWidth =
                (effBrushSize * viewTransform.currentScale).coerceAtLeast(1.5f)

            // 2.2 绘制对称镜像分支 (仅当引擎未走实时镜像时作为回退预览)
            // s12: 正常情况下镜像由各分支独立 paintop 实时出真墨, 这里不画。
            // 仅在引擎未走实时镜像 (!v.symmetryMirrorLive) 时画近似预览。
            // 主笔一侧不画预览: 它的真墨每批都经合成进画布, 再盖线抬笔会跳。
            if (!v.symmetryMirrorLive) {
                for (b in 0 until mirroredSamples.size) {
                    val buf = mirroredSamples[b]
                    val n = mirroredSizes[b]
                    if (n < 1) continue
                    val stride = SYMMETRY_SAMPLE_STRIDE
                    viewTransform.docToScreen(buf[0], buf[1], pointScratch)
                    if (n == 1) {
                        val lutIdx = (buf[2].coerceIn(0f, 1f) * 128f + 0.5f).toInt().coerceIn(0, 128)
                        val r = (baseStrokeWidth * symmetryPressureLut[lutIdx] * 0.5f)
                            .coerceAtLeast(0.75f)
                        canvas.drawCircle(pointScratch[0], pointScratch[1], r, mirroredPointPaint)
                        continue
                    }
                    var prevX = pointScratch[0]
                    var prevY = pointScratch[1]
                    var prevP = buf[2]
                    for (i in 1 until n) {
                        val base = i * stride
                        viewTransform.docToScreen(buf[base], buf[base + 1], pointScratch)
                        val curP = buf[base + 2]
                        val avgP = (prevP + curP) * 0.5f
                        val lutIdx = (avgP.coerceIn(0f, 1f) * 128f + 0.5f).toInt().coerceIn(0, 128)
                        mirroredDrawPaint.strokeWidth =
                            (baseStrokeWidth * symmetryPressureLut[lutIdx]).coerceAtLeast(1.5f)
                        canvas.drawLine(prevX, prevY, pointScratch[0], pointScratch[1], mirroredDrawPaint)
                        prevX = pointScratch[0]
                        prevY = pointScratch[1]
                        prevP = curP
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 2.5 绘画中辅助吸附动态导引线 (透视灭点射线 / 等轴测 / 2D 网格高亮)
    // =========================================================================
    val dynamicGuideKind = computeDynamicGuide(v, dynamicGuideSeg)
    if (dynamicGuideKind == DYNAMIC_GUIDE_ABORT_FRAME) return
    if (FrontBufferHolePolicy.windowGuideHolesOnly(
            FrontBufferFeatureFlags.snapHold, realInk.composeStroke, realInk.held,
            frontBufferOverlay?.guideOnScreen == true,
        )) {
        // 按住真墨 (或松开后前缓冲导引线尚未擦掉): 孔洞外由前缓冲画导引线, 窗口只补孔洞内与
        // UI 避让区内, 同一像素不会两个表面同时画。
        drawHeldGuide(canvas, dynamicGuideKind, dynamicGuideSeg)
    } else {
        dynamicGuidePainter.draw(canvas, dynamicGuideKind, dynamicGuideSeg)
    }

    // =========================================================================
    // 3. 光标及对称参考线绘制 (Cursor & Guides)
    // 依据用户的光标模式设置独立渲染
    // =========================================================================
    // H-3: 按住真墨时孔洞内的光标由窗口画 (与前缓冲同一函数/同一参数, 只取孔洞部分)。
    drawHoleCursor(canvas)

    if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return
    if (!hasLocalCursorPos) return
    // 局部非空 Offset 为内联值类, 无堆分配
    val pos = Offset(localCursorX, localCursorY)
    val isEraser = effTool() == Tool.ERASER
    val cursorMode = if (isEraser) v.eraserCursorMode else v.brushCursorMode
    // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
    val shouldShow = when (cursorMode) {
        1 -> localIsTouching
        2 -> localIsHovering
        3 -> localIsTouching || localIsHovering
        else -> false
    }
    val isDrawTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY
    // Phase 2: 记录本帧光标环的屏幕位置与半径 —— 下一帧做局部失效时要把环的"旧位置"也覆盖掉
    lqRingValid = false
    if (shouldShow && isDrawTool && v.cursorStyleMode != 4) {
        // 绘画中且前缓冲预测生效时，光标圆心自动对齐至预测延伸笔尖 (对称模式下不使用预测笔尖)
        val effectiveCursorPos = pos

        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val cursorBrushSize = when {
            effTool() == Tool.LIQUIFY -> liquifyBrushSize
            effTool() == Tool.ERASER && tool != Tool.ERASER -> v.getToolEffectiveSize("eraser").toFloat()
            else -> v.brushSize.toFloat()
        }
        val holdEmaSnapshot = smoothedCursorPressure
        val pressureFraction = if (localIsTouching) {
            if (v.frontBufferPredictionEnabled) {
                // 前缓冲预测: 预测压力 + EMA 阻尼 (防光标环呼吸抖动)
                val targetPressure = localPressure
                smoothedCursorPressure = if (smoothedCursorPressure < 0f) {
                    targetPressure
                } else {
                    smoothedCursorPressure * 0.65f + targetPressure * 0.35f
                }
                pressureFractionCached(smoothedCursorPressure)
            } else {
                // 上游原版: 直接使用当前实际压力, 无平滑
                smoothedCursorPressure = -1f
                pressureFractionCached(localPressure)
            }
        } else {
            smoothedCursorPressure = -1f
            1f
        }
        val brushRadiusScreen = CursorOutlineGeometry.outlineRadius(
            cursorBrushSize, scale, pressureFraction, v.cursorOutlineReflectsActualSize,
        )
        // 按住期间这段 EMA 归前缓冲光标 (noteHoldCursor) 独占: 窗口因孔洞/UI 裁剪重画时不能
        // 再推进一次, 否则前缓冲光标半径会随窗口重画频率变化。
        if (realInk.held) smoothedCursorPressure = holdEmaSnapshot
        // 半径取"环半径"与"十字/点光标尺寸"的较大者: 后三种样式画的是小十字/圆点, 半径只有
        // 几个 dp, 但同样需要被覆盖
        lqRingValid = true
        lqRingCx = effectiveCursorPos.x
        lqRingCy = effectiveCursorPos.y
        lqRingR = if (brushRadiusScreen > 8f * density) brushRadiusScreen else 8f * density

        prevCursorLeft = effectiveCursorPos.x - lqRingR - 4f
        prevCursorTop = effectiveCursorPos.y - lqRingR - 4f
        prevCursorRight = effectiveCursorPos.x + lqRingR + 4f
        prevCursorBottom = effectiveCursorPos.y + lqRingR + 4f
        prevCursorRectValid = true

        val clips = uiClipSnapshot
        val shouldDraw = !realInk.held || !clips.isEmpty
        if (shouldDraw) {
            var symCursorCount = 0
            if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
                ensureViewTransform()
                viewTransform.screenToDoc(effectiveCursorPos.x, effectiveCursorPos.y, pointScratch)
                symCursorCount = v.drawingGuide.writeSymmetricPoints(
                    pointScratch[0], pointScratch[1],
                    v.docWidth, v.docHeight,
                    symScratchX, symScratchY
                )
                for (i in 0 until symCursorCount) {
                    viewTransform.docToScreen(symScratchX[i], symScratchY[i], pointScratch)
                    cursorSymScreenX[i] = pointScratch[0]
                    cursorSymScreenY[i] = pointScratch[1]
                }
            }

            fun drawBrushCursor(c: Canvas) {
                when (v.cursorStyleMode) {
                    0 -> { // 双对比圆环
                        c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                        c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen, cursorPaintWhite)
                    }
                    1 -> { // 十字准星 (极细发丝相交线)
                        val len = 7f * density
                        c.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintBlack)
                        c.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintBlack)
                        c.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintWhite)
                        c.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintWhite)
                    }
                    2 -> { // 精确点
                        c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, 3f * density, cursorPaintBlack)
                        c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, 1.8f * density, cursorPaintWhite)
                    }
                    5 -> { // 圆 + 十字
                        c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                        c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen, cursorPaintWhite)
                        val len = 4.5f * density
                        c.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintBlack)
                        c.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintBlack)
                        c.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintWhite)
                        c.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintWhite)
                    }
                    6 -> { // 笔刷真实轮廓 (Brush Outline)
                        val polys = v.brushOutlinePolygons
                        if (polys != null && polys.isNotEmpty()) {
                            brushOutlinePath.rewind()
                            for (poly in polys) {
                                val count = poly.size / 2
                                if (count >= 2) {
                                    val startX = poly[0] * brushRadiusScreen
                                    val startY = poly[1] * brushRadiusScreen
                                    brushOutlinePath.moveTo(startX, startY)
                                    for (i in 1 until count) {
                                        val px = poly[i * 2] * brushRadiusScreen
                                        val py = poly[i * 2 + 1] * brushRadiusScreen
                                        brushOutlinePath.lineTo(px, py)
                                    }
                                    brushOutlinePath.close()
                                }
                            }
                            val baseRot = (v.brushAngle + v.brushRotation).toFloat()
                            val motionRot = if (v.brushFollowDirection || v.brushRotationSensor == "drawingangle") localCursorAngle else 0f
                            val totalRot = baseRot + motionRot + canvasRotation

                            c.save()
                            c.translate(effectiveCursorPos.x, effectiveCursorPos.y)
                            c.rotate(totalRot)
                            c.drawPath(brushOutlinePath, brushOutlinePaintBlack)
                            c.restore()
                        } else {
                            // 回退单黑细圆环
                            c.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen, brushOutlinePaintBlack)
                        }
                    }
                }

                // 对称辅助光标镜像绘制 (支持垂直/水平/四象限/径向多分支，零分配复用预算屏幕坐标)
                for (i in 0 until symCursorCount) {
                    val sx = cursorSymScreenX[i]
                    val sy = cursorSymScreenY[i]
                    c.drawCircle(sx, sy, brushRadiusScreen + 0.8f, cursorPaintBlack)
                    c.drawCircle(sx, sy, brushRadiusScreen, cursorPaintWhite)
                }
            }

            if (!realInk.held) {
                drawBrushCursor(canvas)
            } else {
                for (i in 0 until clips.count) {
                    canvas.save()
                    canvas.clipRect(clips.left(i), clips.top(i), clips.right(i), clips.bottom(i))
                    drawBrushCursor(canvas)
                    canvas.restore()
                }
            }
        }
    }
}

/** 动态吸附导引线 (与 onDraw 同一个 [computeDynamicGuide]) 写进前缓冲快照。零分配。 */
internal fun CanvasTouchView.snapshotHoldGuide(v: PaintViewModel) {
    val raw = computeDynamicGuide(v, holdGuideSeg)
    val kind = if (raw == DYNAMIC_GUIDE_ABORT_FRAME) DynamicGuidePainter.KIND_NONE else raw
    realInk.guideSnapshot.write(kind, holdGuideSeg)
    invalidateHeldGuide(kind)
}

/**
 * 按住期间窗口只画"导引线 ∩ (孔洞 ∪ UI 避让区)"; 导引线变了就重画新旧两处的这部分。
 * 孔洞判定沿线段每 8px 一次 O(1) [com.reverie.paint.model.HoleMaskGrid.anyInRect];
 * 没有孔洞也不压避让区时零失效。UI 线程, 零分配。
 */
internal fun CanvasTouchView.invalidateHeldGuide(kind: Int) {
    val seg = holdGuideSeg
    val visible = kind == DynamicGuidePainter.KIND_LINE || kind == DynamicGuidePainter.KIND_LINE_VP
    if (visible && kind == holdGuidePrevKind) {
        var same = true
        for (i in 0 until DynamicGuidePainter.SEG_SIZE) if (seg[i] != holdGuidePrevSeg[i]) { same = false; break }
        if (same) return
    }
    val acc = holdGuideBox
    DynamicGuideFootprint.resetAcc(acc)
    if (visible && realInk.held) {
        val pad = dynamicGuidePainter.linePad
        val vpHalf = dynamicGuidePainter.vpHalf
        val holes = realInk.strokeHoles
        if (holes != null) {
            DynamicGuideFootprint.accumulateSegmentHoleHits(holes.grid, seg[0], seg[1], seg[2], seg[3], pad, acc,
                holdGuideScratch)
            if (kind == DynamicGuidePainter.KIND_LINE_VP) {
                DynamicGuideFootprint.accumulateBoxHoleHit(holes.grid, seg[4], seg[5], vpHalf, acc)
            }
        }
        val clips = uiClipSnapshot
        for (i in 0 until clips.count) {
            val l = clips.left(i)
            val t = clips.top(i)
            val r = clips.right(i)
            val b = clips.bottom(i)
            DynamicGuideFootprint.accumulateSegmentRectHit(seg[0], seg[1], seg[2], seg[3], pad, l, t, r, b, acc,
                holdGuideScratch)
            if (kind == DynamicGuidePainter.KIND_LINE_VP) {
                DynamicGuideFootprint.accumulateSegmentRectHit(seg[4], seg[5], seg[4], seg[5], vpHalf, l, t, r, b,
                    acc, holdGuideScratch)
            }
        }
    }
    val prev = holdGuidePrevBox
    val curEmpty = DynamicGuideFootprint.isEmptyAcc(acc)
    val prevEmpty = DynamicGuideFootprint.isEmptyAcc(prev)
    holdGuidePrevKind = if (visible) kind else DynamicGuidePainter.KIND_NONE
    if (visible) System.arraycopy(seg, 0, holdGuidePrevSeg, 0, DynamicGuidePainter.SEG_SIZE)
    if (curEmpty && prevEmpty) return
    var l = Int.MAX_VALUE
    var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE
    var b = Int.MIN_VALUE
    if (!prevEmpty) { l = prev[0]; t = prev[1]; r = prev[2]; b = prev[3] }
    if (!curEmpty) {
        l = minOf(l, acc[0]); t = minOf(t, acc[1]); r = maxOf(r, acc[2]); b = maxOf(b, acc[3])
    }
    System.arraycopy(acc, 0, prev, 0, 4)
    val il = maxOf(l, 0)
    val it = maxOf(t, 0)
    val ir = minOf(r, width)
    val ib = minOf(b, height)
    if (ir > il && ib > it) postInvalidateOnAnimation(il, it, ir, ib)
}

internal fun CanvasTouchView.resetHeldGuide() {
    holdGuidePrevKind = DynamicGuidePainter.KIND_NONE
    DynamicGuideFootprint.resetAcc(holdGuidePrevBox)
}

/**
 * 按住期间窗口画的那一半导引线: UI 避让区内 (前缓冲在那里被 clipOut) 照常画, 其余只画孔洞内
 * (二值掩码 DST_IN, 与前缓冲的 DST_OUT 像素互补)。两半拼起来与关低延迟时的 [DynamicGuidePainter.draw]
 * 一致。没有孔洞且不压避让区时什么都不画。
 */
internal fun CanvasTouchView.drawHeldGuide(canvas: Canvas, kind: Int, seg: FloatArray) {
    if (kind != DynamicGuidePainter.KIND_LINE && kind != DynamicGuidePainter.KIND_LINE_VP) return
    val clips = uiClipSnapshot
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        for (i in 0 until clips.count) {
            canvas.save()
            canvas.clipRect(clips.left(i), clips.top(i), clips.right(i), clips.bottom(i))
            // 避让区彼此重叠时只在第一个里画, 不叠两次。
            for (j in 0 until i) canvas.clipOutRect(clips.left(j), clips.top(j), clips.right(j), clips.bottom(j))
            dynamicGuidePainter.draw(canvas, kind, seg)
            canvas.restore()
        }
    }
    val holes = realInk.strokeHoles ?: return
    val bmp = holes.bitmap ?: return
    if (bmp.isRecycled) return
    canvas.save()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        for (i in 0 until clips.count) canvas.clipOutRect(clips.left(i), clips.top(i), clips.right(i), clips.bottom(i))
    }
    dynamicGuidePainter.drawMasked(canvas, kind, seg, bmp, android.graphics.PorterDuff.Mode.DST_IN)
    canvas.restore()
}

/**
 * 像素网格 (scale >= 4)。调用方已把 [canvas] 变换到"位图居中"坐标 (与 drawCanvas 同一套)。
 * 主画布每帧画; 前缓冲重放在落笔时也调用它录进 Picture (见 [FrontBufferDecorRecorder])。
 * 只画视口内可见的网格线, 视口已把整幅包进来时线条仍可能过万, 宁可不画网格也不掉帧。
 */
internal fun CanvasTouchView.drawPixelGrid(canvas: Canvas, v: PaintViewModel, scale: Float, sceneW: Int, sceneH: Int) {
    if (!CanvasDecorGeometry.pixelGridDrawn(v.pixelGridEnabled, scale)) return
    val gridAlpha = CanvasDecorGeometry.pixelGridAlpha(scale)
    pixelGridPaint.color = android.graphics.Color.argb((gridAlpha * 255).toInt(), 255, 255, 255)
    pixelGridPaint.strokeWidth = 1f / scale
    ensureViewTransform()
    visibleBitmapBounds(boundsScratch)
    val gx0 = boundsScratch[0].coerceIn(0, sceneW)
    val gx1 = boundsScratch[2].coerceIn(0, sceneW)
    val gy0 = boundsScratch[1].coerceIn(0, sceneH)
    val gy1 = boundsScratch[3].coerceIn(0, sceneH)
    val totalLines = CanvasDecorGeometry.pixelGridLineCount(gx0, gx1, gy0, gy1)
    if (totalLines !in 1..MAX_VISIBLE_GRID_LINES) return
    val neededFloats = totalLines * 4
    if (gridLinesBuffer.size < neededFloats) {
        gridLinesBuffer = FloatArray(neededFloats)
    }
    val n = CanvasDecorGeometry.fillPixelGridLines(
        gridLinesBuffer, gx0, gx1, gy0, gy1, sceneW / 2f, sceneH / 2f,
    )
    canvas.drawLines(gridLinesBuffer, 0, n, pixelGridPaint)
}

/**
 * 渲染线程写完一帧后调用: 只让真正变化的区域重绘 (配合渲染路径分离,
 * 位图不变的区域不再重绘, 省下大画幅下每帧的整屏 GPU 填充)。
 *
 * 任何"会在脏区之外重绘"的覆盖层处于活动状态时 (光标 / 预测笔迹 /
 * 镜像笔迹 / 像素网格 / 画布旋转 / 变换会话) 一律回退全量重绘 ——
 * 宁可多画一次, 也不允许出现边缘残影。
 */
internal fun CanvasTouchView.invalidateFromRender() {
    lastInvalidateFromRenderUptimeNs = SystemClock.elapsedRealtimeNanos()
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
        postInvalidate()
        return
    }
    val v = vm
    val snap = v?.renderDirtySnapshot
    if (!partialInvalidateEnabled || v == null || snap == null) {
        postInvalidate()
        return
    }
    val dw = snap[2]
    val dh = snap[3]
    if (dw <= 0 || dh <= 0 || !canPartialInvalidate(v, dw, dh)) {
        postInvalidate()
        return
    }
    ensureViewTransform()
    viewTransform.bitmapRectToScreenBounds(
        snap[0].toFloat(),
        snap[1].toFloat(),
        (snap[0] + dw).toFloat(),
        (snap[1] + dh).toFloat(),
        renderBoundsScratch,
    )
    var left = renderBoundsScratch[0].coerceAtLeast(0)
    var top = renderBoundsScratch[1].coerceAtLeast(0)
    var right = renderBoundsScratch[2].coerceAtMost(viewW)
    var bottom = renderBoundsScratch[3].coerceAtMost(viewH)
    if (right <= left || bottom <= top) {
        postInvalidate()
        return
    }
    // 标尺(debug)必须整块重绘: 它的行数会随数据出现/消失, 只刷新损坏区会留下两代文本
    // 拼接的残迹。标尺关闭时这里是纯读一次布尔(见 PerfHud.fillHudBounds)。
    if (PerfHud.fillHudBounds(hudBoundsScratch)) {
        if (hudBoundsScratch.left < left) left = hudBoundsScratch.left
        if (hudBoundsScratch.top < top) top = hudBoundsScratch.top
        if (hudBoundsScratch.right > right) right = hudBoundsScratch.right
        if (hudBoundsScratch.bottom > bottom) bottom = hudBoundsScratch.bottom
    }
    postInvalidate(left, top, right, bottom)
}

/** 局部失效的安全条件, 任一条不满足就整屏重绘 */
internal fun CanvasTouchView.canPartialInvalidate(v: PaintViewModel, dirtyW: Int, dirtyH: Int): Boolean {
    // 回放与动画播放按帧整体切换画面, 只失效引擎回报的脏区会留下上一帧残影
    if (v.currentPage == Page.REPLAY || v.anim.isPlaying) return false
    if (!viewTransform.isAxisAligned) return false
    if (v.pixelGridEnabled && viewTransform.currentScale >= 4f) return false
    if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return false
    if (hasLocalCursorPos || localIsTouching || localIsHovering) return false
    if (hasPredictedScreenPoint) return false
    if (isTransformActive || isInteracting) return false
    val guide = v.drawingGuide
    if (guide.mode == GuideMode.SYMMETRY && guide.assistedDrawing && mirrorBranchHasSamples()) return false
    val viewArea = viewW.toLong() * viewH.toLong()
    if (viewArea <= 0L) return false
    // 脏区超过视口一半时就省不下什么了, 整屏一次画完更划算
    return dirtyW.toLong() * dirtyH.toLong() * 2L <= viewArea
}

/**
 * Phase 2: 局部失效的安全条件。与 [canPartialInvalidate] 的唯一区别是**不再因光标活动而整屏**
 * —— 液化手势期间光标环必然活动且 `isInteracting` 恒为 true, 若沿用旧条件该优化等于不存在。
 * 改为把环的前后位置并进失效矩形; 其余"会在脏区之外重绘"的覆盖层仍一律整屏。
 */
internal fun CanvasTouchView.canLiquifyPartialInvalidate(v: PaintViewModel): Boolean {
    if (v.currentPage == Page.REPLAY || v.anim.isPlaying) return false
    if (!viewTransform.isAxisAligned) return false
    if (v.pixelGridEnabled) return false
    if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return false
    if (isTransformActive || isPinchMotion || maxTouchPointers >= 2) return false
    val guide = v.drawingGuide
    // 对称镜像会在光标之外多画几个环, 不在失效矩形内 ⇒ 整屏
    if (guide.mode == GuideMode.SYMMETRY && guide.assistedDrawing) return false
    return viewW > 0 && viewH > 0
}
