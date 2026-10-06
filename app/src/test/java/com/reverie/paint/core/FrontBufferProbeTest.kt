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
}
