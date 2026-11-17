package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFilesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `同步写入文件成功且无残留临时文件`() {
        val file = File(tempFolder.root, "sub/test.txt")
        AtomicFiles.writeText(file, "hello world")
        assertTrue(file.exists())
        assertEquals("hello world", file.readText())
        assertFalse(File(tempFolder.root, "sub/test.txt.tmp").exists())
    }

    @Test
    fun `异步写入经flushPending屏障后必定落盘`() {
        val file = File(tempFolder.root, "async/async.json")
        AtomicFiles.writeTextAsync(file, "{\"status\": \"ok\"}")
        AtomicFiles.flushPending()
        assertTrue(file.exists())
        assertEquals("{\"status\": \"ok\"}", file.readText())
    }

    @Test
    fun `高频连续异步写入自动合并为最新内容`() {
        val file = File(tempFolder.root, "coalesce/latest.txt")
        for (i in 1..20) {
            AtomicFiles.writeTextAsync(file, "value_$i")
        }
        AtomicFiles.flushPending()
        assertTrue(file.exists())
        assertEquals("value_20", file.readText())
    }

    @Test
    fun `原子覆盖已有文件正确更新内容`() {
        val file = File(tempFolder.root, "overwrite.txt")
        AtomicFiles.writeText(file, "initial")
        assertEquals("initial", file.readText())

        AtomicFiles.writeText(file, "updated_content")
        assertEquals("updated_content", file.readText())
    }
}
