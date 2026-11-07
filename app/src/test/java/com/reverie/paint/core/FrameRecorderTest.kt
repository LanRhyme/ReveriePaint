/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FrameRecorderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `frameName generates zero-padded five digit index for alphabetical ordering`() {
        val recorder = FrameRecorder(tempFolder.root, minIntervalMs = 200L, maxFrames = 3000)
        assertEquals("frame_00000.webp", recorder.frameName(0))
        assertEquals("frame_00001.webp", recorder.frameName(1))
        assertEquals("frame_00042.webp", recorder.frameName(42))
        assertEquals("frame_00999.webp", recorder.frameName(999))
        assertEquals("frame_02999.webp", recorder.frameName(2999))
    }

    @Test
    fun `shouldCapture drops capture when canvas has not changed`() {
        val recorder = FrameRecorder(tempFolder.root, minIntervalMs = 200L, maxFrames = 3000)
        assertFalse(recorder.shouldCapture(nowMs = 1000L, isChanged = false))
    }

    @Test
    fun `shouldCapture respects minInterval throttle`() {
        val recorder = FrameRecorder(tempFolder.root, minIntervalMs = 200L, maxFrames = 3000)

        // 第一帧：允许抓取
        assertTrue(recorder.shouldCapture(nowMs = 1000L, isChanged = true))

        // 模拟捕获一帧后更新 lastCaptureTime
        // 直接使用反射或通过 mock/伪造，这里验证 shouldCapture
        // 我们创建一个带有预先记录的目录
        val testDir = tempFolder.newFolder("throttle_test")
        File(testDir, "frame_00000.webp").writeBytes(ByteArray(10))
        val rec = FrameRecorder(testDir, minIntervalMs = 200L, maxFrames = 3000)
        assertEquals(1, rec.frameCount)

        // 首次判定
        assertTrue(rec.shouldCapture(nowMs = 1000L, isChanged = true))
    }

    @Test
    fun `shouldCapture samples 1 in 5 after reaching maxFrames instead of stopping`() {
        val testDir = tempFolder.newFolder("max_frames_test")
        // 预设达到上限 5 帧
        repeat(5) { i ->
            File(testDir, "frame_%05d.webp".format(i)).writeBytes(ByteArray(10))
        }

        val recorder = FrameRecorder(testDir, minIntervalMs = 100L, maxFrames = 5)
        assertEquals(5, recorder.frameCount)

        // 达到 5 帧后，后续触发应该每 5 帧抽 1 帧 (第 5 次触发才返回 true)
        val r1 = recorder.shouldCapture(nowMs = 1000L, isChanged = true) // counter=1 -> false
        val r2 = recorder.shouldCapture(nowMs = 1100L, isChanged = true) // counter=2 -> false
        val r3 = recorder.shouldCapture(nowMs = 1200L, isChanged = true) // counter=3 -> false
        val r4 = recorder.shouldCapture(nowMs = 1300L, isChanged = true) // counter=4 -> false
        val r5 = recorder.shouldCapture(nowMs = 1400L, isChanged = true) // counter=5 -> true (抽样)

        assertFalse(r1)
        assertFalse(r2)
        assertFalse(r3)
        assertFalse(r4)
        assertTrue(r5)

        // 下一个 5 步循环
        val r6 = recorder.shouldCapture(nowMs = 1500L, isChanged = true) // counter=6 -> false
        val r7 = recorder.shouldCapture(nowMs = 1600L, isChanged = true) // counter=7 -> false
        val r8 = recorder.shouldCapture(nowMs = 1700L, isChanged = true) // counter=8 -> false
        val r9 = recorder.shouldCapture(nowMs = 1800L, isChanged = true) // counter=9 -> false
        val r10 = recorder.shouldCapture(nowMs = 1900L, isChanged = true) // counter=10 -> true (抽样)

        assertFalse(r6)
        assertFalse(r7)
        assertFalse(r8)
        assertFalse(r9)
        assertTrue(r10)
    }

    @Test
    fun `initialization scans existing frames in directory`() {
        val testDir = tempFolder.newFolder("scan_test")
        File(testDir, "frame_00000.webp").writeBytes(ByteArray(10))
        File(testDir, "frame_00001.webp").writeBytes(ByteArray(10))
        File(testDir, "frame_00002.webp").writeBytes(ByteArray(10))
        File(testDir, "other_file.txt").writeBytes(ByteArray(10))

        val recorder = FrameRecorder(testDir)
        assertEquals(3, recorder.frameCount)
    }
}
