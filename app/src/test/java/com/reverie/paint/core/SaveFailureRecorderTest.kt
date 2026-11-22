/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveFailureRecorderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("save_fail_test").toFile()
        SaveFailureRecorder.dirOverrideForTest = tempDir
    }

    @After
    fun tearDown() {
        SaveFailureRecorder.clear()
        SaveFailureRecorder.dirOverrideForTest = null
        tempDir.deleteRecursively()
    }

    @Test
    fun `SaveErrorInfo 序列化与反序列化保留全部关键诊断字段`() {
        val info = SaveErrorInfo(
            ok = false,
            stage = "write_entry",
            errno = 28,
            detail = "Failed to write zip entry layer_003.png: errno=28",
            timestamp = 1732264800000L,
            targetPath = "/data/user/0/com.reverie.paint/files/projects/demo.revp",
            isAutoSave = false,
            docWidth = 4096,
            docHeight = 4096,
            layerCount = 10,
            availableDiskBytes = 12 * 1024 * 1024L,
            totalDiskBytes = 256L * 1024 * 1024 * 1024L,
            availableRamBytes = 5000L * 1024 * 1024L,
            totalRamBytes = 16000L * 1024 * 1024L,
            nativeHeapAllocatedBytes = 2000L * 1024 * 1024L,
            jvmHeapAllocatedBytes = 64L * 1024 * 1024L,
            appVersion = "1.10.1",
        )

        val json = info.toJson()
        val parsed = SaveErrorInfo.fromJson(json)
        assertNotNull(parsed)
        assertEquals(info.ok, parsed!!.ok)
        assertEquals(info.stage, parsed.stage)
        assertEquals(info.errno, parsed.errno)
        assertEquals(info.detail, parsed.detail)
        assertEquals(info.timestamp, parsed.timestamp)
        assertEquals(info.targetPath, parsed.targetPath)
        assertEquals(info.isAutoSave, parsed.isAutoSave)
        assertEquals(info.docWidth, parsed.docWidth)
        assertEquals(info.docHeight, parsed.docHeight)
        assertEquals(info.layerCount, parsed.layerCount)
        assertEquals(info.availableDiskBytes, parsed.availableDiskBytes)
        assertEquals(info.nativeHeapAllocatedBytes, parsed.nativeHeapAllocatedBytes)
        assertEquals(info.appVersion, parsed.appVersion)
    }

    @Test
    fun `SaveFailureRecorder 记录持久化并在超限时滚动淘汰老记录`() {
        repeat(7) { idx ->
            val info = SaveErrorInfo(
                ok = false,
                stage = "stage_$idx",
                errno = idx,
                detail = "detail_$idx",
                timestamp = 1000L + idx,
                targetPath = "/path/test_$idx.revp",
            )
            SaveFailureRecorder.recordFailure(null, info)
        }

        val all = SaveFailureRecorder.getAllFailures()
        assertEquals(5, all.size)
        // 倒序排列，最新的是 index 6
        assertEquals("stage_6", all.first().stage)
        assertEquals("stage_2", all.last().stage)

        val latest = SaveFailureRecorder.getLatestFailure()
        assertNotNull(latest)
        assertEquals("stage_6", latest!!.stage)
    }

    @Test
    fun `formatForDiagnostics 在无记录时输出空提示，有记录时格式化详细指标`() {
        assertTrue(SaveFailureRecorder.formatForDiagnostics().contains("No save failures recorded"))

        val info = SaveErrorInfo(
            ok = false,
            stage = "commit_failed",
            errno = 2,
            detail = "Both rename and copy failed to commit tmp",
            timestamp = 1732264800000L,
            targetPath = "/data/user/0/com.reverie.paint/files/projects/art.revp",
            isAutoSave = true,
            docWidth = 2048,
            docHeight = 2048,
            layerCount = 5,
            availableDiskBytes = 50 * 1024 * 1024L,
            totalDiskBytes = 128L * 1024 * 1024 * 1024L,
            availableRamBytes = 4000L * 1024 * 1024L,
            totalRamBytes = 8000L * 1024 * 1024L,
            nativeHeapAllocatedBytes = 300L * 1024 * 1024L,
            jvmHeapAllocatedBytes = 40L * 1024 * 1024L,
        )
        SaveFailureRecorder.recordFailure(null, info)

        val report = SaveFailureRecorder.formatForDiagnostics()
        assertTrue(report.contains("Recent Project Save Failures"))
        assertTrue(report.contains("AutoSave: stage=commit_failed, errno=2"))
        assertTrue(report.contains("Target: /data/user/0/com.reverie.paint/files/projects/art.revp"))
        assertTrue(report.contains("Canvas: 2048x2048, 5 layers"))
        assertTrue(report.contains("50 MB free"))
        assertTrue(report.contains("NativeHeap: 300 MB"))
    }

    @Test
    fun `失败原因归类优先磁盘满再内存不足再 IO`() {
        assertEquals(
            SaveFailureKind.DISK_FULL,
            SaveErrorInfo(ok = false, stage = "write_entry", errno = SaveErrorInfo.ERRNO_ENOSPC).failureKind(),
        )
        assertEquals(
            SaveFailureKind.DISK_FULL,
            SaveErrorInfo(ok = false, stage = "write_entry", availableDiskBytes = 10L * 1024 * 1024).failureKind(),
        )
        assertEquals(
            SaveFailureKind.DISK_FULL,
            SaveErrorInfo(ok = false, stage = SaveErrorInfo.STAGE_DISK_PRECHECK).failureKind(),
        )
        // 取不到 StatFs 时 availableDiskBytes=0 是"未知"，不能据此判磁盘满
        assertEquals(
            SaveFailureKind.IO,
            SaveErrorInfo(ok = false, stage = "write_entry", availableDiskBytes = 0L).failureKind(),
        )
        assertEquals(
            SaveFailureKind.OUT_OF_MEMORY,
            SaveErrorInfo(ok = false, stage = "write_entry", nativeHeapAllocatedBytes = 3L * 1024 * 1024 * 1024).failureKind(),
        )
        // native 堆未到绝对值阈值，但已占设备内存 60% 以上：同样按内存压力归类
        assertEquals(
            SaveFailureKind.OUT_OF_MEMORY,
            SaveErrorInfo(
                ok = false,
                stage = "write_entry",
                nativeHeapAllocatedBytes = 1900L * 1024 * 1024,
                totalRamBytes = 3L * 1024 * 1024 * 1024,
            ).failureKind(),
        )
        // 堆大小与设备内存都不异常时，不能靠 nativeHeap 误判成内存不足
        assertEquals(
            SaveFailureKind.IO,
            SaveErrorInfo(
                ok = false,
                stage = "commit_failed",
                nativeHeapAllocatedBytes = 900L * 1024 * 1024,
                totalRamBytes = 8L * 1024 * 1024 * 1024,
            ).failureKind(),
        )
        assertEquals(
            SaveFailureKind.IO,
            SaveErrorInfo(ok = false, stage = "commit_failed").failureKind(),
        )
        assertEquals(
            SaveFailureKind.UNKNOWN,
            SaveErrorInfo(ok = false, stage = "not_yet_classified").failureKind(),
        )
    }

    @Test
    fun `成功记录不落盘且诊断输出带原因分类`() {
        SaveFailureRecorder.recordFailure(null, SaveErrorInfo(ok = true, stage = "none"))
        assertTrue(SaveFailureRecorder.getAllFailures().isEmpty())

        SaveFailureRecorder.recordFailure(
            null,
            SaveErrorInfo(ok = false, stage = "write_entry", errno = SaveErrorInfo.ERRNO_ENOSPC, timestamp = 1L),
        )
        val report = SaveFailureRecorder.formatForDiagnostics()
        assertTrue(report.contains("stage=write_entry, errno=28, reason=DISK_FULL"))
    }
}
