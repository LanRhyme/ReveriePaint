/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt
import com.reverie.paint.model.*

internal fun PaintViewModel.animationApplyOnionSkin() {
    val effectiveOnion = anim.onionSkin || anim.isTemporaryOnionSkin
    if (!effectiveOnion) {
        runCore(after = {}) {
            ReverieCoreBridge.configureOnionSkin(
                false, 0, 0, 0, anim.onionTint,
                anim.onionColorBackward, anim.onionColorForward,
            )
        }
        return
    }

    val layer = anim.selectedTrack.takeIf { it >= 0 } ?: currentLayerIndex
    val cur = anim.currentTime

    val offsets = mutableListOf<Int>()
    val opacities = mutableListOf<Int>()

    if (anim.onionKeyframesOnly) {
        val times = anim.keyframeCache[layer].orEmpty()
        val prevTimes = times.filter { it < cur }.takeLast(anim.onionPrev)
        val nextTimes = times.filter { it > cur }.take(anim.onionNext)

        val pCount = prevTimes.size
        for (i in prevTimes.indices) {
            val t = prevTimes[i]
            val off = t - cur
            val dist = pCount - i
            val op = calcDecayOpacity(dist, pCount, anim.onionOpacity, anim.onionDecayMode)
            offsets.add(off)
            opacities.add(op)
        }

        val nCount = nextTimes.size
        for (i in nextTimes.indices) {
            val t = nextTimes[i]
            val off = t - cur
            val dist = i + 1
            val op = calcDecayOpacity(dist, nCount, anim.onionOpacity, anim.onionDecayMode)
            offsets.add(off)
            opacities.add(op)
        }
    } else {
        for (i in 1..anim.onionPrev) {
            offsets.add(-i)
            opacities.add(calcDecayOpacity(i, anim.onionPrev, anim.onionOpacity, anim.onionDecayMode))
        }
        for (i in 1..anim.onionNext) {
            offsets.add(i)
            opacities.add(calcDecayOpacity(i, anim.onionNext, anim.onionOpacity, anim.onionDecayMode))
        }
    }

    runCore(after = {}) {
        ReverieCoreBridge.configureOnionSkinExplicit(
            true,
            offsets.toIntArray(),
            opacities.toIntArray(),
            anim.onionTint,
            anim.onionColorBackward,
            anim.onionColorForward,
        )
    }
}

internal fun calcDecayOpacity(
    distance: Int,
    total: Int,
    maxOpacity: Int,
    mode: OnionDecayMode,
): Int {
    if (total <= 0) return 0
    return when (mode) {
        OnionDecayMode.LINEAR -> {
            val factor = (total - distance + 1).toFloat() / total.toFloat()
            (maxOpacity * factor.coerceIn(0.15f, 1.0f)).roundToInt().coerceIn(0, 255)
        }
        OnionDecayMode.SMOOTH -> {
            val factor = Math.pow(0.62, (distance - 1).toDouble()).toFloat()
            (maxOpacity * factor).roundToInt().coerceIn(0, 255)
        }
        OnionDecayMode.CONSTANT -> {
            maxOpacity.coerceIn(0, 255)
        }
    }
}

// ============================================================
// 透光台对位 (Shift & Trace)
// ============================================================

/** 开启 / 关闭透光台对位模式 (Shift & Trace) */
internal fun PaintViewModel.animationToggleShiftTrace() {
    val willActive = !anim.shiftTraceActive
    anim.shiftTraceActive = willActive
    if (willActive) {
        anim.shiftTraceGestureMode = ShiftTraceGestureMode.ALIGN_FRAME
        animationFetchShiftTraceBitmaps()
        if (anim.onionSkin) {
            renderHandler?.post {
                ReverieCoreBridge.setOnionSkinSuppressed(true)
                doRender()
            }
        }
    } else {
        anim.shiftTracePrevBitmap?.recycle()
        anim.shiftTracePrevBitmap = null
        anim.shiftTraceNextBitmap?.recycle()
        anim.shiftTraceNextBitmap = null
        if (anim.onionSkin) {
            renderHandler?.post {
                ReverieCoreBridge.setOnionSkinSuppressed(false)
                doRender()
            }
        }
    }
}

/** 复位透光台参考帧偏移: target 为空则全部复位 */
internal fun PaintViewModel.animationResetShiftTrace(target: ShiftTraceTarget? = null) {
    when (target) {
        ShiftTraceTarget.PREV -> anim.shiftTracePrevTransform = ShiftTransform()
        ShiftTraceTarget.NEXT -> anim.shiftTraceNextTransform = ShiftTransform()
        null -> {
            anim.shiftTracePrevTransform = ShiftTransform()
            anim.shiftTraceNextTransform = ShiftTransform()
        }
    }
}

/** 异步拉取前一帧与后一帧的全画幅参考位图 (供 Overlay 硬件加速渲染) */
internal fun PaintViewModel.animationFetchShiftTraceBitmaps() {
    val cur = anim.currentTime
    val layer = anim.selectedTrack.takeIf { it >= 0 } ?: currentLayerIndex
    val times = anim.keyframeCache[layer].orEmpty()
    val prevT = times.filter { it < cur }.maxOrNull() ?: (cur - 1).takeIf { it >= 0 }
    val nextT = times.filter { it > cur }.minOrNull() ?: (cur + 1).takeIf { it < anim.length }

    renderHandler?.post {
        val w = renderW
        val h = renderH
        if (w <= 0 || h <= 0) return@post

        var pBmp: Bitmap? = null
        if (prevT != null && prevT >= 0) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = ReverieCoreBridge.renderKeyframeFull(layer, prevT, bmp)
            if (ok) pBmp = bmp else bmp.recycle()
        }

        var nBmp: Bitmap? = null
        if (nextT != null && nextT >= 0) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = ReverieCoreBridge.renderKeyframeFull(layer, nextT, bmp)
            if (ok) nBmp = bmp else bmp.recycle()
        }

        mainHandler.post {
            anim.shiftTracePrevBitmap?.recycle()
            anim.shiftTracePrevBitmap = pBmp
            anim.shiftTraceNextBitmap?.recycle()
            anim.shiftTraceNextBitmap = nBmp
        }
    }
}

/** 临时透光: 按住洋葱皮按钮触发 */
internal fun PaintViewModel.animationStartTemporaryOnionSkin() {
    if (anim.onionSkin || anim.isTemporaryOnionSkin) return
    anim.isTemporaryOnionSkin = true
    animationApplyOnionSkin()
}

internal fun PaintViewModel.animationEndTemporaryOnionSkin() {
    if (!anim.isTemporaryOnionSkin) return
    anim.isTemporaryOnionSkin = false
    animationApplyOnionSkin()
}

/** 临时翻帧比对 (Flip Peek): 按住上一帧极速预览前一关键帧, 松手立刻还原 */
internal fun PaintViewModel.animationStartFlipPeek() {
    if (anim.isFlipPeeking) return
    val cur = anim.currentTime
    val layer = anim.selectedTrack.takeIf { it >= 0 } ?: currentLayerIndex
    val times = anim.keyframeCache[layer].orEmpty()
    val prev = times.filter { it < cur }.maxOrNull() ?: (cur - 1).coerceAtLeast(0)
    if (prev == cur) return
    anim.isFlipPeeking = true
    anim.flipOriginalTime = cur
    anim.currentTime = prev
    renderHandler?.post {
        ReverieCoreBridge.setAnimationCurrentTime(prev, false)
        doRender()
    }
}

internal fun PaintViewModel.animationEndFlipPeek() {
    if (!anim.isFlipPeeking) return
    val orig = anim.flipOriginalTime
    anim.isFlipPeeking = false
    anim.flipOriginalTime = -1
    if (orig >= 0) {
        anim.currentTime = orig
        renderHandler?.post {
            ReverieCoreBridge.setAnimationCurrentTime(orig, false)
            doRender()
        }
    }
}

// ============================================================
// 导入: 图像序列帧 / 视频 / 音频
// ============================================================

/** 当前帧起插入图像序列帧的目标轨道 (选中轨道, 回退当前图层); 渲染线程内调用 */
