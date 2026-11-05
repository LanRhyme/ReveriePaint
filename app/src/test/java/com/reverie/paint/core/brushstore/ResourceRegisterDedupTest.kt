/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 资源去重与防冲突测试（S5：统一走 ResourceStore.register）。
 */
class ResourceRegisterDedupTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeRoot: File
    private lateinit var brushesDir: File
    private lateinit var patternsDir: File
    private lateinit var store: ResourceStore

    @Before
    fun setUp() {
        val root = tempFolder.root
        storeRoot = File(root, "brush_store").apply { mkdirs() }
        brushesDir = File(root, "brushes").apply { mkdirs() }
        patternsDir = File(root, "patterns").apply { mkdirs() }
        store = ResourceStore(storeRoot, brushesDir, patternsDir)
    }

    @Test
    fun `相同 md5 注册多次只存一份且返回相同 Ref`() {
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        val ref1 = store.register(bytes, ResourceStore.Kind.TIP, "my_tip.png")
        val ref2 = store.register(bytes, ResourceStore.Kind.TIP, "another_tip.png")

        assertEquals(ref1.md5, ref2.md5)
        assertEquals(ref1.file, ref2.file)
        assertEquals("my_tip.png", ref1.file)

        val file = store.fileFor(ref1)
        assertTrue(file.exists())
        assertArrayEquals(bytes, file.readBytes())

        val tipFiles = brushesDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertEquals(1, tipFiles.size)
    }

    @Test
    fun `不同内容相同文件名自动重命名且两者并存`() {
        val bytes1 = byteArrayOf(1, 2, 3)
        val bytes2 = byteArrayOf(4, 5, 6)

        val ref1 = store.register(bytes1, ResourceStore.Kind.TIP, "custom.png")
        val ref2 = store.register(bytes2, ResourceStore.Kind.TIP, "custom.png")

        assertNotEquals(ref1.md5, ref2.md5)
        assertNotEquals(ref1.file, ref2.file)
        assertEquals("custom.png", ref1.file)
        assertTrue(ref2.file.startsWith("custom_"))
        assertTrue(ref2.file.endsWith(".png"))

        val file1 = store.fileFor(ref1)
        val file2 = store.fileFor(ref2)
        assertTrue(file1.exists())
        assertTrue(file2.exists())
        assertArrayEquals(bytes1, file1.readBytes())
        assertArrayEquals(bytes2, file2.readBytes())
    }

    @Test
    fun `相同字节分别作为 TIP 和 PATTERN 注册分别落盘互不混淆`() {
        val bytes = byteArrayOf(7, 8, 9, 10)
        val tipRef = store.register(bytes, ResourceStore.Kind.TIP, "shared.png")
        val patRef = store.register(bytes, ResourceStore.Kind.PATTERN, "shared.png")

        assertEquals(tipRef.md5, patRef.md5)
        assertEquals(ResourceStore.Kind.TIP, tipRef.kind)
        assertEquals(ResourceStore.Kind.PATTERN, patRef.kind)

        val tipFile = store.fileFor(tipRef)
        val patFile = store.fileFor(patRef)
        assertEquals(brushesDir, tipFile.parentFile)
        assertEquals(patternsDir, patFile.parentFile)
        assertTrue(tipFile.exists())
        assertTrue(patFile.exists())
    }
}
