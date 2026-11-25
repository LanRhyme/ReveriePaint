/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

/**
 * 真墨按住策略 (纯决策, 无 Android 依赖)。
 *
 * [overlayPanelsBlockHold] 对应 CanvasTouchView.overlayPanelsOpen: 全屏/半模态设置类面板开着时
 * RealInkController.wantsHold 必须返回 false (前缓冲置顶会盖住它们且无法靠孔径裁剪露出来)。
 * 绘图辅助面板是悬浮设置窗, 靠 [UiPanelSlot] + clipOutUi 露面板, 不得挡按住。
 *
 * 判据 (Round 7): 只有"开着时画布根本收不到落笔"的面板才挡按住 —— 它们的全屏透明遮罩
 * `noRippleClickable(onClose)` 吃掉画布上的每个按下, 这一笔不存在, 挡不挡按住对墨无影响,
 * 而遮罩是全屏的, 无法登记成有限孔径。画布可落笔的面板一律走 [UiPanelSlot] 孔径, 不挡按住。
 */
internal object RealInkHoldPolicy {
    fun shouldReleaseOnReject(held: Boolean, wantsHold: Boolean): Boolean = held && !wantsHold

    /**
     * 软件尾线兜底判定 (in-window Canvas):
     * - 关闭前缓冲低延迟时: 维持上游原版 OEM 预测尾线开关与白名单门控
     * - 开启前缓冲低延迟但本笔未能按住真墨 (!realInkHeld) 时: 对 TIER_1/TIER_2 笔刷回退绘制轻量软件尾线
     */
    fun shouldDrawSoftwarePredictionTail(
        frontBufferPredictionEnabled: Boolean,
        realInkHeld: Boolean,
        stylusStrokePredictionEnabled: Boolean,
        legacyBrushEligible: Boolean,
        frontBufferFallbackEligible: Boolean,
    ): Boolean {
        if (!frontBufferPredictionEnabled) {
            return stylusStrokePredictionEnabled && legacyBrushEligible
        }
        return !realInkHeld && frontBufferFallbackEligible
    }

    /** 软件预测尾线起点不透明度系数: 前缓冲降级回退的 TIER_2 笔刷用更轻的 0.35 减弱突变 */
    fun softwarePredictionTailAlphaFactor(
        frontBufferPredictionEnabled: Boolean,
        isTier2Fallback: Boolean,
    ): Float = if (frontBufferPredictionEnabled && isTier2Fallback) 0.35f else 0.55f

    /**
     * 判断当前工具是否为低延迟前缓冲预测线/预测器支持的画笔工具。
     * 预测线只适用于常规笔刷；侧键临时切橡皮或橡皮/涂抹一律不画预测线。
     */
    fun isRealInkTool(tool: Tool, effTool: Tool): Boolean =
        tool == Tool.BRUSH && effTool == Tool.BRUSH

    /**
     * 判断当前生效工具是否支持真墨按住 (恢复 e474ec11 规则, 支持画笔、橡皮与涂抹)。
     */
    fun isHoldToolSupported(effTool: Tool): Boolean =
        effTool == Tool.BRUSH || effTool == Tool.ERASER || effTool == Tool.SMUDGE

    /**
     * 前缓冲不透明烘焙与 3D 图层门控支持判定:
     * - 当无棋盘格背景时, 前缓冲无法执行不透明烘焙 (半透明笔刷会露底/与普通模式不同色)
     * - 当有可见 3D 图层时, 前缓冲置顶会遮挡 3D 图层
     * 两种情况均不支持按住真墨, 退回普通模式渲染。
     */
    fun bakeSupported(hasCheckerboard: Boolean, has3DVisible: Boolean): Boolean =
        hasCheckerboard && !has3DVisible

    /**
     * 抬笔追赶判定: 当且仅当笔已抬起、追赶已激活、目标版本号已落定且窗口已画到或超过目标版本时,
     * 才允许清空前缓冲。
     */
    fun shouldClearAfterDraw(held: Boolean, armed: Boolean, drawnRev: Long, targetRev: Long): Boolean =
        !held && armed && targetRev != Long.MAX_VALUE && drawnRev >= targetRev

    /** 笔刷面板: 未钉住 = 全屏遮罩 (BrushPanel.kt 未钉住分支 `noRippleClickable(onClose)`); 钉住 = PINNED_BRUSH_PANEL 孔径。 */
    fun brushPanelBlocksHold(open: Boolean, pinned: Boolean): Boolean = open && !pinned

    /**
     * 图层面板: 未钉住且不在滤镜调节页 = 全屏遮罩; 钉住 = PINNED_LAYER_PANEL 孔径;
     * 未钉住的滤镜调节页没有遮罩 (画布可落笔), 卡片登记 LAYER_FILTER_ADJUST_CARD 孔径, 不挡按住。
     */
    fun layerPanelBlocksHold(open: Boolean, pinned: Boolean, filterAdjustPassThrough: Boolean): Boolean =
        open && !pinned && !filterAdjustPassThrough

    /** 色板: 未钉住 = 全屏遮罩 (ColorPanel.kt 未钉住分支); 钉住 = PINNED_COLOR_PANEL 孔径。 */
    fun colorPanelBlocksHold(open: Boolean, pinned: Boolean): Boolean = open && !pinned

    /**
     * @param brushPanelBlocking 笔刷面板开着且未钉住
     * @param layerPanelBlocking 见 [layerPanelBlocksHold]
     * @param colorPanelBlocking 色板开着且未钉住
     * @param settingsPanelOpen 设置半模态
     * @param moreToolsOpen 更多工具半模态
     * @param drawingGuidePanelOpen 绘图辅助悬浮窗 (不挡按住; 参数保留以免接线处漏改)
     * @param modalDialogOpen 模态对话框/文字输入/全局对话框/图层拖拽浮层等全屏阻挡状态
     */
    fun overlayPanelsBlockHold(
        brushPanelBlocking: Boolean,
        layerPanelBlocking: Boolean,
        colorPanelBlocking: Boolean,
        settingsPanelOpen: Boolean,
        moreToolsOpen: Boolean,
        drawingGuidePanelOpen: Boolean = false,
        modalDialogOpen: Boolean = false,
    ): Boolean {
        // drawingGuidePanelOpen 故意不参与: 见 KDoc。
        return brushPanelBlocking || layerPanelBlocking || colorPanelBlocking ||
            settingsPanelOpen || moreToolsOpen || modalDialogOpen
    }
}
