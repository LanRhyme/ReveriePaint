/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Picture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.currentPaintingPanelIds
import com.reverie.paint.core.panels
import com.reverie.paint.core.selectedPanelId
import com.reverie.paint.model.CanvasDecorGeometry
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.DrawingGuideConfig

/**
 * 真墨按住期间要在前缓冲里重画的画布装饰 (L-01)。
 *
 * 前缓冲是置顶 SurfaceView, 真墨脏区用 SRC 贴不透明的瓦片像素, 窗口层里画布之上的装饰
 * (辅助线、选区蚂蚁线、像素网格) 在这些区域被盖住。落笔时把装饰录成 [Picture], GL 线程贴完
 * 真墨后只在贴过的区域重放, 再画光标 —— 不多出一帧, 不逐帧分配。
 *
 * 不可变: UI 线程每次重录都 new 一个实例再整体发布 (@Volatile), GL 线程可能正在重放旧的,
 * 绝不在原对象上重录。
 *
 * - [under]: 主画布 (CanvasTouchView) 里、动态吸附线之下的装饰 —— 像素网格;
 * - [over]: Compose 覆盖层 (CanvasOverlay) 里的装饰 —— 选区蚂蚁线、绘图辅助线 (同一顺序)。
 */
internal class FrontBufferDecor(
    val under: Picture?,
    val over: Picture?,
    /** 录制时的位图→屏幕变换; 与包的 pose 不一致时不重放 (宁可缺装饰也不画错位)。 */
    val pose: CanvasPose,
) {
    val isEmpty: Boolean get() = under == null && over == null
}

/**
 * 动态吸附导引线的快照 (UI 线程每次 MOVE 写, GL 线程贴墨时读)。顺序锁: 写前写后各自增一次
 * 版本号, 读端版本号为奇数或前后不一致就重读 (最多 3 次, 仍失败本包不画导引线)。零分配。
 */
internal class DynamicGuideSnapshot {
    @Volatile private var seq = 0
    private val seg = FloatArray(DynamicGuidePainter.SEG_SIZE)
    private var kind = DynamicGuidePainter.KIND_NONE

    /** UI 线程。 */
    fun write(newKind: Int, src: FloatArray) {
        seq++
        kind = newKind
        if (newKind == DynamicGuidePainter.KIND_LINE || newKind == DynamicGuidePainter.KIND_LINE_VP) {
            for (i in 0 until DynamicGuidePainter.SEG_SIZE) seg[i] = src[i]
        }
        seq++
    }

    /** UI 线程。 */
    fun clear() {
        seq++
        kind = DynamicGuidePainter.KIND_NONE
        seq++
    }

    /** GL 线程: 拷到 [out], 返回 kind。 */
    fun read(out: FloatArray): Int {
        repeat(3) {
            val s0 = seq
            if (s0 and 1 != 0) return@repeat
            val k = kind
            for (i in 0 until DynamicGuidePainter.SEG_SIZE) out[i] = seg[i]
            if (seq == s0) return k
        }
        return DynamicGuidePainter.KIND_NONE
    }
}

private val dummyPanelInteraction = PanelInteractionState()

/**
 * 录制 [FrontBufferDecor] (UI 线程)。只在落笔和"装饰状态变了"时录, 平时每个 MOVE 只做几次引用比较。
 *
 * 可见性判据与窗口层一字不差:
 *  - 绘图辅助线: [CanvasDecorGeometry.guidesVisible] (mode != OFF, 不看 assistedDrawing),
 *    且直接复用 CanvasOverlay 调用的同一个 [drawDrawingGuides];
 *  - 蚂蚁线: [CanvasDecorGeometry.selectionOutlineVisible] + [CanvasDecorDraw.drawSelectionAnts];
 *    运笔时当前工具是画笔/橡皮/涂抹, 不可能"正在框选", 所以 isSelecting = false; 流动相位冻结为 0;
 *  - 像素网格: [CanvasTouchView.drawPixelGrid] (同一函数, 同一阈值)。
 * 选区斑马纹只在选区工具下出现 (CanvasOverlay), 运笔时不可见, 不录。
 */
internal class FrontBufferDecorRecorder(private val view: CanvasTouchView) {
    private val drawScope = CanvasDrawScope()
    private val density = Density(view.context)

    // 上次录制所依据的状态 (引用比较; 都是不可变快照或 Compose state 里整体替换的对象)。
    private var keyGuide: DrawingGuideConfig? = null
    private var keyOutline: androidx.compose.ui.graphics.Path? = null
    private var keyHasSelection = false
    private var keyTransform = false
    private var keyGrid = false
    private var keyPanels: Any? = null
    private var keySelectedPanelId: Long? = null
    private var keyCurrentLayerIndex: Int = -1
    private var keyPose: CanvasPose? = null
    private var keyRenderW = -1
    private var keyRenderH = -1
    private var keyDocW = -1
    private var keyDocH = -1
    private var keyGuidePanelOpen = false
    private var keyViewW = -1
    private var keyViewH = -1
    private var keyZoom = Float.NaN
    private var keyFit = Float.NaN
    private var hasKey = false

    /** 装饰状态与上次录制不同 (或从未录过)。 */
    fun isStale(v: PaintViewModel, pose: CanvasPose, isTransform: Boolean): Boolean {
        if (!hasKey) return true
        return keyGuide !== v.drawingGuide ||
            keyOutline !== v.selectionOutlinePath ||
            keyHasSelection != v.hasSelection ||
            keyTransform != isTransform ||
            keyGrid != v.pixelGridEnabled ||
            keyPanels !== v.panels ||
            keySelectedPanelId != v.selectedPanelId ||
            keyCurrentLayerIndex != v.currentLayerIndex ||
            keyPose != pose ||
            keyRenderW != v.renderW || keyRenderH != v.renderH ||
            keyDocW != v.docWidth || keyDocH != v.docHeight ||
            keyGuidePanelOpen != v.drawingGuidePanelOpen ||
            keyViewW != view.width || keyViewH != view.height ||
            keyZoom != view.canvasZoom || keyFit != view.canvasFitScale
    }

    fun invalidate() {
        hasKey = false
        keyGuide = null
        keyOutline = null
        keyPanels = null
        keySelectedPanelId = null
        keyCurrentLayerIndex = -1
        keyPose = null
    }

    /**
     * 当前是否有任何画在画布之上的装饰可见 (与 [record] 同一判据, 不录制, 只做几次比较)。
     * 落笔时掩码过期, 用它区分"没有装饰 → 无孔洞直接按住"和"有装饰 → 这一笔走常规路径"。
     */
    fun anyVisible(v: PaintViewModel, pose: CanvasPose, isTransform: Boolean): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        if (CanvasDecorGeometry.pixelGridDrawn(v.pixelGridEnabled, pose.scale)) return true
        val imgW = CanvasDecorGeometry.overlayBitmapSize(v.renderW, v.docWidth)
        val imgH = CanvasDecorGeometry.overlayBitmapSize(v.renderH, v.docHeight)
        if (imgW <= 0 || imgH <= 0) return false
        if (CanvasDecorGeometry.guidesVisible(v.drawingGuide)) return true
        if (v.panels.isNotEmpty() && v.currentPaintingPanelIds().isNotEmpty()) return true
        return v.selectionOutlinePath != null && CanvasDecorGeometry.selectionOutlineVisible(
            hasSelection = v.hasSelection,
            isTransform = isTransform,
            isSelecting = false,
            selectionMode = v.selectionMode,
        )
    }

    /**
     * 按当前状态录一份; 什么都不可见时返回 null。[forMask] = true 时录孔洞掩码的源:
     * 蚂蚁线画成覆盖全部虚线相位的实线 ([CanvasDecorDraw.drawSelectionAntsMask]), 其余与窗口同一函数。
     */
    fun record(
        v: PaintViewModel,
        pose: CanvasPose,
        isTransform: Boolean,
        forMask: Boolean = false,
        antsPhase: Float = 0f,
    ): FrontBufferDecor? {
        val w = view.width
        val h = view.height
        hasKey = true
        keyGuide = v.drawingGuide
        keyOutline = v.selectionOutlinePath
        keyHasSelection = v.hasSelection
        keyTransform = isTransform
        keyGrid = v.pixelGridEnabled
        keyPanels = v.panels
        keySelectedPanelId = v.selectedPanelId
        keyCurrentLayerIndex = v.currentLayerIndex
        keyPose = pose
        keyRenderW = v.renderW
        keyRenderH = v.renderH
        keyDocW = v.docWidth
        keyDocH = v.docHeight
        keyGuidePanelOpen = v.drawingGuidePanelOpen
        keyViewW = w
        keyViewH = h
        keyZoom = view.canvasZoom
        keyFit = view.canvasFitScale
        if (w <= 0 || h <= 0) return null

        val gridOn = CanvasDecorGeometry.pixelGridDrawn(v.pixelGridEnabled, pose.scale)
        val guidesOn = CanvasDecorGeometry.guidesVisible(v.drawingGuide)
        val outline = v.selectionOutlinePath
        val antsOn = outline != null && CanvasDecorGeometry.selectionOutlineVisible(
            hasSelection = v.hasSelection,
            isTransform = isTransform,
            isSelecting = false,
            selectionMode = v.selectionMode,
        )
        val imgW = CanvasDecorGeometry.overlayBitmapSize(v.renderW, v.docWidth).toFloat()
        val imgH = CanvasDecorGeometry.overlayBitmapSize(v.renderH, v.docHeight).toFloat()
        val panelsOn = v.panels.isNotEmpty() && v.currentPaintingPanelIds().isNotEmpty() && imgW > 0f && imgH > 0f
        val overlayOn = (guidesOn || antsOn || panelsOn) && imgW > 0f && imgH > 0f

        val under = if (gridOn) {
            Picture().also { pic ->
                val c = pic.beginRecording(w, h)
                c.translate(pose.centerX, pose.centerY)
                c.rotate(pose.rotation)
                c.scale(pose.scale, pose.scale)
                if (pose.signX < 0f || pose.signY < 0f) c.scale(pose.signX, pose.signY)
                view.drawPixelGrid(c, v, pose.scale, pose.bmpW, pose.bmpH)
                pic.endRecording()
            }
        } else {
            null
        }
        val over = if (overlayOn) {
            Picture().also { pic ->
                val c = androidx.compose.ui.graphics.Canvas(pic.beginRecording(w, h))
                val zoom = view.canvasZoom
                val fit = view.canvasFitScale
                drawScope.draw(density, LayoutDirection.Ltr, c, Size(w.toFloat(), h.toFloat())) {
                    // 与 CanvasOverlay 的 withTransform 同一顺序 (= CanvasTouchView.drawCanvas / CanvasPose)。
                    translate(pose.centerX, pose.centerY) {
                        rotate(pose.rotation, pivot = Offset.Zero) {
                            scale(pose.scale, pose.scale, pivot = Offset.Zero) {
                                scale(pose.signX, pose.signY, pivot = Offset.Zero) {
                                    if (antsOn) {
                                        if (forMask) {
                                            with(CanvasDecorDraw) { drawSelectionAntsMask(outline!!, pose.scale) }
                                        } else {
                                            with(CanvasDecorDraw) { drawSelectionAnts(outline!!, pose.scale, antsPhase) }
                                        }
                                    }
                                    drawDrawingGuides(
                                        vm = v,
                                        bmpWidth = imgW,
                                        bmpHeight = imgH,
                                        zoom = zoom,
                                        fitScale = fit,
                                    )
                                    if (panelsOn) {
                                        drawPanelsOverlay(
                                            vm = v,
                                            interaction = dummyPanelInteraction,
                                            bmpWidth = imgW,
                                            bmpHeight = imgH,
                                            viewScale = zoom * fit,
                                            toolActive = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                pic.endRecording()
            }
        } else {
            null
        }
        if (under == null && over == null) return null
        return FrontBufferDecor(under, over, pose)
    }
}
