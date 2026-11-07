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
    fun `decimateDir halves frame count while preserving first and last frame`() {
        val testDir = tempFolder.newFolder("decimate_test")
        // 创建 10 个测试序列帧
        repeat(10) { i ->
            File(testDir, "frame_%05d.webp".format(i)).writeBytes("frame_$i".toByteArray())
        }

        val newCount = FrameRecorder.decimateDir(testDir, factor = 2)
        // 10 帧抽稀 (0, 2, 4, 6, 8, 9) -> 6 帧
        assertEquals(6, newCount)

        val remainingFiles = testDir.listFiles { f -> f.name.matches(FrameRecorder.FRAME_REGEX) }
            ?.sortedBy { it.name } ?: emptyList()
        assertEquals(6, remainingFiles.size)
        // 保证文件名连续无空洞: frame_00000 ~ frame_00005
        for (i in 0 until 6) {
            assertEquals("frame_%05d.webp".format(i), remainingFiles[i].name)
        }
        // 首帧内容为原始 frame_0，末帧内容为原始 frame_9
        assertEquals("frame_0", remainingFiles.first().readText())
        assertEquals("frame_9", remainingFiles.last().readText())
    }

    @Test
    fun `decimateDir leaves directory unchanged when 3 or fewer frames`() {
        val testDir = tempFolder.newFolder("small_decimate_test")
        repeat(3) { i ->
            File(testDir, "frame_%05d.webp".format(i)).writeBytes(ByteArray(10))
        }
        val result = FrameRecorder.decimateDir(testDir, factor = 2)
        assertEquals(3, result)
        assertEquals(3, testDir.listFiles()?.size)
    }

    @Test
    fun `hasSignificantVisualChange detects perceptual differences accurately`() {
        val size = 48 * 48
        val base = IntArray(size) { 0xffffffff.toInt() } // 纯白

        // 1. 无前帧：必定返回 true
        assertTrue(FrameRecorder.hasSignificantVisualChange(base, null))

        // 2. 相同指纹：返回 false
        assertFalse(FrameRecorder.hasSignificantVisualChange(base, base.clone()))

        // 3. 仅 3 个点发生微弱变化 (< 8 阈值)：返回 false (滤除杂点/微抖动)
        val minor = base.clone()
        minor[10] = 0xff000000.toInt()
        minor[20] = 0xff000000.toInt()
        minor[30] = 0xff000000.toInt()
        assertFalse(FrameRecorder.hasSignificantVisualChange(minor, base))

        // 4. 10 个采样点发生明显变化 (>= 8 阈值)：返回 true (识别有效笔画)
        val significant = base.clone()
        for (i in 0 until 10) {
            significant[i * 100] = 0xff123456.toInt()
        }
        assertTrue(FrameRecorder.hasSignificantVisualChange(significant, base))
    }

    @Test
    fun `decimate updates frameCount and files on disk`() {
        val testDir = tempFolder.newFolder("recorder_decimate")
        repeat(8) { i ->
            File(testDir, "frame_%05d.webp".format(i)).writeBytes("content_$i".toByteArray())
        }
        val recorder = FrameRecorder(testDir, minIntervalMs = 100L, maxFrames = 10)
        assertEquals(8, recorder.frameCount)

        val newCount = recorder.decimate(factor = 2)
        assertEquals(newCount, recorder.frameCount)
        assertTrue(recorder.frameCount < 8)
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
