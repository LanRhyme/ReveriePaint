/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.Project
import com.reverie.paint.model.WaveOffset
import com.reverie.paint.model.WaveRect

/**
 * 画廊 ↔ 工程扭曲波转场的解耦宿主接口。
 *
 * 位于 `core/` 层, 仅依赖 `model/`, 由顶层 Compose 容器 `DistortionWaveHost` 实现并注册,
 * 严格保持 `ui/ -> core/ -> model/` 单向依赖。
 */
interface GalleryWaveTransitionHost {
    /**
     * 从画廊打开作品: 截取当前画廊画面进入卡片蓄力态, 后台加载工程并等待首帧就绪后放波。
     *
     * @param project 要打开的工程
     * @param cardBoundsPx 作品卡片在窗口根坐标系下的像素矩形 (缺省则居中扩散)
     * @param focusPx 用户点击位置在窗口根坐标系下的像素坐标
     * @param delayCaptureFrames 截图前等待的帧数 (如从长按弹出菜单触发时等待菜单收起)
     * @return true 表示已由转场托管; false 表示宿主忙或不可用, 调用方应回退到直接 `loadProject`
     */
    fun tryOpenProjectFromGallery(
        project: Project,
        cardBoundsPx: WaveRect?,
        focusPx: WaveOffset?,
        delayCaptureFrames: Int = 0,
    ): Boolean

    /**
     * 从绘画页返回画廊: 先截取当前画布画面, 再执行 [performGoHome] 切回画廊并播放快速收拢波。
     *
     * @return true 表示已由转场托管 (宿主会在截图后调用 [performGoHome]); false 表示调用方应直接执行
     */
    fun tryReturnToGallery(performGoHome: () -> Unit): Boolean

    /**
     * 从新建画布页创建画布: 截取新建画布页画面作为始发态, 核心线程异步创建文档并在首帧就绪后放波扫屏。
     *
     * @param focusPx 用户点击创建按钮的位置在窗口根坐标系下的像素坐标 (缺省则从屏幕中下部扩散)
     * @param performCreate 执行新建画布的核心逻辑
     * @return true 表示已由转场托管; false 表示宿主忙或不可用, 调用方直接执行 [performCreate]
     */
    fun tryCreateCanvas(
        focusPx: WaveOffset?,
        performCreate: () -> Unit,
    ): Boolean
}
