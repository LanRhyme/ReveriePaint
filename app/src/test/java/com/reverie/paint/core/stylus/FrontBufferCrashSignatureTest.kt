/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前缓冲崩溃签名判定的回归测试。
 *
 * 背景: `FrontBufferProbe` 的隔离原先只认日志里出现 `libgraphics-core.so`, 但线上
 * 占比最大的那一簇 (vivo/Xiaomi Android 13) 栈里只有 `libgui.so` + `libandroid_runtime.so`,
 * 从未被隔离, 于是同一台机器反复崩 (单机 6 条上报)。
 */
class FrontBufferCrashSignatureTest {

    private fun log(
        signal: String = "SIGSEGV",
        fault: String = "0x0000000000000000",
        appState: String = "FrontBuffer: pref=true graphicsCore=1.1.0-beta01",
        frames: String = "libgui.so+0xc5b94\nlibgui.so+0x11dbdc\nlibandroid_runtime.so+0x143444",
    ): String = buildString {
        appendLine("===================================================")
        appendLine("          ReveriePaint Native Crash Report         ")
        appendLine("===================================================")
        appendLine("App Version: 1.6.3")
        appendLine("Signal: $signal (code=1)")
        appendLine("Fault Address: $fault")
        appendLine("Crashed Thread PID: 1234, TID: 5678")
        appendLine(frames)
        appendLine()
        appendLine("--- [App State Snapshot] ---")
        appendLine(appState)
        appendLine()
        appendLine("--- [Recent Breadcrumbs] ---")
        appendLine("- [12:00:00] Navigation page")
    }

    @Test
    fun `matches androidx graphics core teardown crash`() {
        val text = log(frames = "libgraphics-core.so+0x1234\nlibgui.so+0x5678")
        assertTrue(FrontBufferCrashSignature.matches(text))
    }

    @Test
    fun `matches libgui surface teardown with front buffer enabled`() {
        assertTrue(FrontBufferCrashSignature.matches(log()))
    }

    @Test
    fun `matches fault address 8 as well`() {
        assertTrue(FrontBufferCrashSignature.matches(log(fault = "0x0000000000000008")))
    }

    @Test
    fun `ignores libgui crash when front buffer was disabled`() {
        assertFalse(
            FrontBufferCrashSignature.matches(
                log(appState = "FrontBuffer: pref=false graphicsCore=1.1.0-beta01"),
            ),
        )
    }

    @Test
    fun `ignores non-null fault addresses`() {
        assertFalse(FrontBufferCrashSignature.matches(log(fault = "0x0000000012345678")))
    }

    @Test
    fun `ignores stacks without the surface jni frame`() {
        assertFalse(
            FrontBufferCrashSignature.matches(log(frames = "libgui.so+0xc5b94\nlibc.so+0x1234")),
        )
    }

    @Test
    fun `ignores non-SIGSEGV signals`() {
        assertFalse(FrontBufferCrashSignature.matches(log(signal = "SIGABRT")))
    }

    @Test
    fun `ignores empty log`() {
        assertFalse(FrontBufferCrashSignature.matches(""))
    }
}
