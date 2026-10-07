/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.Build
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.ui.painting.canvas.FrontBufferPathPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferProbeTest {

    @Test
    fun `min supported API is Android Q`() {
        assertEquals(Build.VERSION_CODES.Q, FrontBufferProbe.MIN_SUPPORTED_API)
        assertEquals(29, FrontBufferProbe.MIN_SUPPORTED_API)
    }

    @Test
    fun `diagnostic summary contains essential system info`() {
        val summary = FrontBufferProbe.getDiagnosticSummary()
        assertNotNull(summary)
        assertTrue("Summary should start with FrontBuffer[", summary.startsWith("FrontBuffer["))
        assertTrue("Summary should contain supported field", summary.contains("supported="))
        assertTrue("Summary should contain sdk field", summary.contains("sdk="))
    }

    @Test
    fun `path packet fields validate correctly`() {
        val packet = FrontBufferPathPacket(path = null, strokeWidth = 5.0f, color = 0x123456)
        assertEquals(5.0f, packet.strokeWidth, 0.001f)
        assertEquals(0x123456, packet.color)
        assertFalse(packet.isClear)

        val clearPacket = FrontBufferPathPacket(path = null, strokeWidth = 0f, color = 0, isClear = true)
        assertTrue(clearPacket.isClear)
    }

    @Test
    fun `no software fallback when overlay mounted and renderer ready`() {
        assertFalse(FrontBufferProbe.softwareFallbackRequired(overlayPresent = true, canRenderPreview = true))
    }

    @Test
    fun `software fallback required when renderer failed to initialize`() {
        // 回退空洞回归: overlay 非空但渲染器初始化失败, 直出是 no-op, 必须回退软件绘制
        assertTrue(FrontBufferProbe.softwareFallbackRequired(overlayPresent = true, canRenderPreview = false))
    }

    @Test
    fun `software fallback required when overlay not mounted`() {
        assertTrue(FrontBufferProbe.softwareFallbackRequired(overlayPresent = false, canRenderPreview = false))
        // 覆盖层未挂载时无论 canRender 参数为何都必须回退
        assertTrue(FrontBufferProbe.softwareFallbackRequired(overlayPresent = false, canRenderPreview = true))
    }

    @Test
    fun `fallback and direct-out gates are strictly complementary`() {
        for (present in listOf(false, true)) {
            for (canRender in listOf(false, true)) {
                val fallback = FrontBufferProbe.softwareFallbackRequired(present, canRender)
                val directOut = present && canRender
                assertTrue("fallback and directOut must be complementary", fallback != directOut)
            }
        }
    }
}
