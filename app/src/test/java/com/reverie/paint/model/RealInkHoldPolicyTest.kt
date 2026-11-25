package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealInkHoldPolicyTest {
    @Test
    fun `reject while held releases, accept does not`() {
        assertTrue(RealInkHoldPolicy.shouldReleaseOnReject(held = true, wantsHold = false))
        assertFalse(RealInkHoldPolicy.shouldReleaseOnReject(held = true, wantsHold = true))
        assertFalse(RealInkHoldPolicy.shouldReleaseOnReject(held = false, wantsHold = false))
    }

    @Test
    fun `drawing guide panel chrome alone does not block real-ink hold`() {
        // 辅助面板是悬浮设置窗, 不是全屏模态: 开着时仍应能按住前缓冲 (靠 UiPanelSlot 挖孔露面板)。
        assertFalse(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = false,
                drawingGuidePanelOpen = true,
            ),
        )
    }

    @Test
    fun `unpinned brush panel still blocks real-ink hold`() {
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = true,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = false,
                drawingGuidePanelOpen = false,
            ),
        )
    }

    @Test
    fun `settings or more-tools modal blocks real-ink hold`() {
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = true,
                moreToolsOpen = false,
                drawingGuidePanelOpen = false,
            ),
        )
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = true,
                drawingGuidePanelOpen = false,
            ),
        )
    }

    // ---- Round 7: 面板策略表 (挡/不挡按住) ----

    @Test
    fun `panel policy table - only scrim-covered panels block hold`() {
        // 笔刷: 未钉住 = 全屏遮罩挡; 钉住 = 孔径不挡; 关着不挡。
        assertTrue(RealInkHoldPolicy.brushPanelBlocksHold(open = true, pinned = false))
        assertFalse(RealInkHoldPolicy.brushPanelBlocksHold(open = true, pinned = true))
        assertFalse(RealInkHoldPolicy.brushPanelBlocksHold(open = false, pinned = false))
        // 色板同理。
        assertTrue(RealInkHoldPolicy.colorPanelBlocksHold(open = true, pinned = false))
        assertFalse(RealInkHoldPolicy.colorPanelBlocksHold(open = true, pinned = true))
        assertFalse(RealInkHoldPolicy.colorPanelBlocksHold(open = false, pinned = false))
        // 图层: 未钉住列表/详情页 = 遮罩挡; 未钉住滤镜调节页无遮罩 = 孔径不挡; 钉住不挡。
        assertTrue(RealInkHoldPolicy.layerPanelBlocksHold(open = true, pinned = false, filterAdjustPassThrough = false))
        assertFalse(RealInkHoldPolicy.layerPanelBlocksHold(open = true, pinned = false, filterAdjustPassThrough = true))
        assertFalse(RealInkHoldPolicy.layerPanelBlocksHold(open = true, pinned = true, filterAdjustPassThrough = false))
        assertFalse(RealInkHoldPolicy.layerPanelBlocksHold(open = true, pinned = true, filterAdjustPassThrough = true))
        assertFalse(RealInkHoldPolicy.layerPanelBlocksHold(open = false, pinned = false, filterAdjustPassThrough = false))
    }

    @Test
    fun `layer filter adjust page alone does not block real-ink hold`() {
        assertFalse(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = RealInkHoldPolicy.layerPanelBlocksHold(
                    open = true, pinned = false, filterAdjustPassThrough = true,
                ),
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = false,
                drawingGuidePanelOpen = true,
            ),
        )
    }

    @Test
    fun `any single scrim panel blocks hold regardless of others`() {
        val inputs = listOf(
            booleanArrayOf(true, false, false, false, false),
            booleanArrayOf(false, true, false, false, false),
            booleanArrayOf(false, false, true, false, false),
            booleanArrayOf(false, false, false, true, false),
            booleanArrayOf(false, false, false, false, true),
        )
        for (a in inputs) {
            for (guide in booleanArrayOf(false, true)) {
                assertTrue(
                    RealInkHoldPolicy.overlayPanelsBlockHold(a[0], a[1], a[2], a[3], a[4], drawingGuidePanelOpen = guide),
                )
            }
        }
        assertFalse(RealInkHoldPolicy.overlayPanelsBlockHold(false, false, false, false, false, drawingGuidePanelOpen = false))
    }

    @Test
    fun `bakeSupported truth table`() {
        // hasCheckerboard = true, has3DVisible = false -> true
        assertTrue(RealInkHoldPolicy.bakeSupported(hasCheckerboard = true, has3DVisible = false))
        // hasCheckerboard = false, has3DVisible = false -> false
        assertFalse(RealInkHoldPolicy.bakeSupported(hasCheckerboard = false, has3DVisible = false))
        // hasCheckerboard = true, has3DVisible = true -> false
        assertFalse(RealInkHoldPolicy.bakeSupported(hasCheckerboard = true, has3DVisible = true))
        // hasCheckerboard = false, has3DVisible = true -> false
        assertFalse(RealInkHoldPolicy.bakeSupported(hasCheckerboard = false, has3DVisible = true))
    }

    @Test
    fun `modal dialog blocks hold even when all panels are closed`() {
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = false,
                drawingGuidePanelOpen = false,
                modalDialogOpen = true,
            )
        )
    }

    @Test
    fun `software prediction tail off front-buffer follows oem toggle and legacy whitelist`() {
        assertTrue(
            RealInkHoldPolicy.shouldDrawSoftwarePredictionTail(
                frontBufferPredictionEnabled = false,
                realInkHeld = false,
                stylusStrokePredictionEnabled = true,
                legacyBrushEligible = true,
                frontBufferFallbackEligible = false,
            )
        )
        assertFalse(
            RealInkHoldPolicy.shouldDrawSoftwarePredictionTail(
                frontBufferPredictionEnabled = false,
                realInkHeld = false,
                stylusStrokePredictionEnabled = false,
                legacyBrushEligible = true,
                frontBufferFallbackEligible = true,
            )
        )
        assertFalse(
            RealInkHoldPolicy.shouldDrawSoftwarePredictionTail(
                frontBufferPredictionEnabled = false,
                realInkHeld = false,
                stylusStrokePredictionEnabled = true,
                legacyBrushEligible = false,
                frontBufferFallbackEligible = true,
            )
        )
    }

    @Test
    fun `software prediction tail on front-buffer falls back only when realInkHeld is false`() {
        assertFalse(
            RealInkHoldPolicy.shouldDrawSoftwarePredictionTail(
                frontBufferPredictionEnabled = true,
                realInkHeld = true,
                stylusStrokePredictionEnabled = false,
                legacyBrushEligible = false,
                frontBufferFallbackEligible = true,
            )
        )
        assertTrue(
            RealInkHoldPolicy.shouldDrawSoftwarePredictionTail(
                frontBufferPredictionEnabled = true,
                realInkHeld = false,
                stylusStrokePredictionEnabled = false,
                legacyBrushEligible = false,
                frontBufferFallbackEligible = true,
            )
        )
        assertFalse(
            RealInkHoldPolicy.shouldDrawSoftwarePredictionTail(
                frontBufferPredictionEnabled = true,
                realInkHeld = false,
                stylusStrokePredictionEnabled = true,
                legacyBrushEligible = true,
                frontBufferFallbackEligible = false,
            )
        )
    }

    @Test
    fun `software prediction tail alpha softens tier2 fallback only when front-buffer enabled`() {
        assertEquals(
            0.35f,
            RealInkHoldPolicy.softwarePredictionTailAlphaFactor(
                frontBufferPredictionEnabled = true,
                isTier2Fallback = true,
            ),
            1e-6f,
        )
        assertEquals(
            0.55f,
            RealInkHoldPolicy.softwarePredictionTailAlphaFactor(
                frontBufferPredictionEnabled = true,
                isTier2Fallback = false,
            ),
            1e-6f,
        )
        assertEquals(
            0.55f,
            RealInkHoldPolicy.softwarePredictionTailAlphaFactor(
                frontBufferPredictionEnabled = false,
                isTier2Fallback = true,
            ),
            1e-6f,
        )
    }
}
