/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FrameRecorder 的边界用例: 感知差异阈值、抽稀命名连续性、上限行为。
 * 只测纯 Kotlin 逻辑, 不依赖 Android Bitmap。
 */
class FrameRecorderEdgeTest {

    private fun tmpDir(name: String): File {
        val d = File(System.getProperty("java.io.tmpdir"), "rp-frame-edge-$name")
        d.deleteRecursively()
        d.mkdirs()
        return d
    }

    private fun writeFrames(dir: File, n: Int): List<File> =
        (0 until n).map { i ->
            File(dir, "frame_%05d.webp".format(i)).apply { writeBytes(byteArrayOf(i.toByte())) }
        }

    @Test
    fun `identical fingerprints report no change`() {
        val a = IntArray(48 * 48) { 0xFF336699.toInt() }
        assertFalse(FrameRecorder.hasSignificantVisualChange(a, a.copyOf()))
    }

    @Test
    fun `below threshold difference is ignored`() {
        val prev = IntArray(48 * 48) { 0xFF808080.toInt() }
        val curr = prev.copyOf()
        // 只改 2 个采样点, 且差值低于阈值 → 不算变化 (minChangedPoints 默认 8)
        curr[0] = 0xFF828282.toInt()
        curr[1] = 0xFF828282.toInt()
        assertFalse(FrameRecorder.hasSignificantVisualChange(curr, prev))
    }

    @Test
    fun `enough changed points report change`() {
        val prev = IntArray(48 * 48) { 0xFF000000.toInt() }
        val curr = prev.copyOf()
        for (i in 0 until 10) curr[i] = 0xFFFFFFFF.toInt()
        assertTrue(FrameRecorder.hasSignificantVisualChange(curr, prev))
    }

    @Test
    fun `previous fingerprint of different size is treated as change`() {
        val curr = IntArray(10) { 0xFF000000.toInt() }
        val prev = IntArray(4) { 0xFF000000.toInt() }
        assertTrue(FrameRecorder.hasSignificantVisualChange(curr, prev))
    }

    @Test
    fun `decimate keeps every other frame plus the last one and renames contiguously`() {
        val dir = tmpDir("decimate")
        writeFrames(dir, 7)
        val count = FrameRecorder.decimateDir(dir, 2)
        // 0,2,4,6 保留 + 末帧 6 已在内 → 4 帧
        assertEquals(4, count)
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf("frame_00000.webp", "frame_00001.webp", "frame_00002.webp", "frame_00003.webp"),
            names,
        )
    }

    @Test
    fun `decimate leaves tiny directories untouched`() {
        val dir = tmpDir("tiny")
        writeFrames(dir, 3)
        assertEquals(3, FrameRecorder.decimateDir(dir, 2))
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `decimate ignores non frame files`() {
        val dir = tmpDir("mixed")
        writeFrames(dir, 5)
        File(dir, "notes.txt").writeText("keep me")
        FrameRecorder.decimateDir(dir, 2)
        assertTrue(File(dir, "notes.txt").exists())
    }

    @Test
    fun `frame count is restored from an existing directory`() {
        val dir = tmpDir("restore")
        writeFrames(dir, 4)
        val rec = FrameRecorder(dir)
        assertEquals(4, rec.frameCount)
        // 新帧应接着已有序号写, 不覆盖历史
        assertEquals("frame_00004.webp", rec.frameName(rec.frameCount))
    }
}
