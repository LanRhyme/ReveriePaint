/* SPDX-License-Identifier: GPL-3.0-or-later */

package com.reverie.paint.core

import android.graphics.Bitmap

/**
 * 切层内容闪现: 切层时把新层内容单独渲染一张低分辨率位图, 由 CanvasOverlay
 * 以 accent 色形状脉冲叠加在合成画面上 (见 CanvasOverlay 的 layerFlash 分支)。
 *
 * 双缓冲: 两张渲染位图 ping-pong, 引擎线程永远写非显示中的那张, 写完经
 * runCore 的 after 在主线程交换给 UI, 避免写与读竞态 (AGENTS.md 双缓冲铁律)。
 * 切层频率低, 位图常驻两份 (长边 2048 上限, 最坏 ~33MB) 是可接受的代价。
 *
 * 线程模型: layerFlashBuffers / layerFlashFrontIdx / layerFlashRequestId 是普通
 * 字段 (非 Compose State), 跨线程可见性由 runCore 的 handler post 提供
 * happens-before (引擎线程写入 → 主线程 after 读取)。op (引擎线程) 全程不读写
 * 任何 Compose State; layerFlashBitmap 的写入只允许出现在主线程路径 (after /
 * cancelLayerContentFlash / clearLayerFlash / updateLayerFlashEnabled)。
 *
 * 状态字段挂在 PaintViewModel 类体 (见 PaintViewModel.kt 的 layerFlash* 字段),
 * 本文件只放扩展函数。
 */

/**
 * 请求一次切层内容闪现: 在引擎线程把第 [index] 层单独渲染进后台位图,
 * 请求号仍是最新的话在主线程交换为显示位图并驱动动画。
 */
internal fun PaintViewModel.requestLayerContentFlash(index: Int) {
    if (!layerFlashEnabled) return
    if (docWidth <= 0 || docHeight <= 0) return
    // C++ 侧 renderLayerCanvas 会先分配整文档尺寸的中间 QImage, 超大画布有
    // 内存尖峰 (8192² = 256MB), 直接跳过不闪
    if (maxOf(docWidth, docHeight) > 4096) return
    layerFlashRequestId++
    val req = layerFlashRequestId
    var ok = false
    runCore(render = false, after = {
        // 快速连续切层时只认最新请求, 过期渲染静默丢弃
        if (req == layerFlashRequestId && ok) {
            layerFlashFrontIdx = 1 - layerFlashFrontIdx
            layerFlashBitmap = layerFlashBuffers?.get(layerFlashFrontIdx)
            layerFlashTick++
        }
    }) {
        val longSide = maxOf(docWidth, docHeight)
        val scale = if (longSide > 2048) 2048f / longSide else 1f
        val w = (docWidth * scale).toInt().coerceAtLeast(1)
        val h = (docHeight * scale).toInt().coerceAtLeast(1)
        val pool = layerFlashBuffers
        val back: Bitmap
        if (pool == null || pool.size != 2 || pool[0].isRecycled || pool[0].width != w || pool[0].height != h) {
            // 尺寸不符 (换文档/改画布尺寸, 频率极低): 整池换新。旧池只丢弃引用、
            // 绝不 recycle —— 旧 front 可能仍被 UI/HWUI DisplayList 持有, recycle
            // 会崩 "trying to use a recycled bitmap", 交给 GC 延迟回收。整池赋值要么
            // 完整成功要么保持旧值, 不会留下半初始化/已 recycle 的池
            val fresh = try {
                arrayOf(
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
                )
            } catch (t: Throwable) {
                android.util.Log.w("ReverieLayerFlash", "createBitmap($w, $h) failed", t)
                null
            }
            if (fresh == null) {
                ok = false
                return@runCore
            }
            layerFlashBuffers = fresh
            layerFlashFrontIdx = 0
            back = fresh[1]
        } else {
            back = pool[1 - layerFlashFrontIdx]
        }
        ok = try {
            ReverieCoreBridge.renderLayerCanvas(index, back)
        } catch (t: Throwable) {
            android.util.Log.w("ReverieLayerFlash", "renderLayerCanvas failed for layer $index", t)
            false
        }
    }
}

/** 落笔/关闭开关时立即取消闪现: 过期化在途请求并摘掉显示位图 (主线程) */
internal fun PaintViewModel.cancelLayerContentFlash() {
    layerFlashRequestId++
    layerFlashBitmap = null
}

/** 动画自然结束时由 CanvasOverlay 调用, 只摘显示位图 (主线程) */
internal fun PaintViewModel.clearLayerFlash() {
    layerFlashBitmap = null
}
