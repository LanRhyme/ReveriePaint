package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class ResourceStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeRoot: File
    private lateinit var brushesDir: File
    private lateinit var patternsDir: File
    private lateinit var store: ResourceStore

    private val testPngTranscoder: (ByteArray) -> ByteArray = { bytes ->
        val img = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("ImageIO failed to decode image")
        val baos = ByteArrayOutputStream()
        check(ImageIO.write(img, "PNG", baos)) { "ImageIO failed to encode PNG" }
        baos.toByteArray()
    }

    private fun createTestJpeg(width: Int = 8, height: Int = 8, color: Color = Color.BLUE): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        val baos = ByteArrayOutputStream()
        check(ImageIO.write(img, "JPEG", baos)) { "ImageIO failed to encode JPEG" }
        return baos.toByteArray()
    }

    @Before
    fun setUp() {
        val root = tempFolder.root
        storeRoot = File(root, "brush_store").apply { mkdirs() }
        brushesDir = File(root, "brushes").apply { mkdirs() }
        patternsDir = File(root, "patterns").apply { mkdirs() }
        store = ResourceStore(
            storeRoot = storeRoot,
            brushesDir = brushesDir,
            patternsDir = patternsDir,
            pngTranscoder = testPngTranscoder,
        )
    }

    @Test
    fun `同字节注册两次返回同一个Ref`() {
        val bytes = "same_brush_bytes".toByteArray()
        val ref1 = store.register(bytes, ResourceStore.Kind.TIP, "tip1.png")
        assertEquals("tip1.png", ref1.file)
        assertEquals(ResourceStore.Kind.TIP, ref1.kind)
        assertEquals(BrushDiagnostics.md5(bytes), ref1.md5)

        val ref2 = store.register(bytes, ResourceStore.Kind.TIP, "tip2.png")
        assertEquals(ref1, ref2)
        assertEquals("tip1.png", ref2.file)

        val brushFiles = brushesDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertEquals(1, brushFiles.size)
        assertEquals("tip1.png", brushFiles[0].name)
    }

    @Test
    fun `同名不同字节另起名且旧文件字节不变`() {
        val bytes1 = "content_A".toByteArray()
        val bytes2 = "content_B".toByteArray()

        val ref1 = store.register(bytes1, ResourceStore.Kind.TIP, "pencil.png")
        assertEquals("pencil.png", ref1.file)

        val ref2 = store.register(bytes2, ResourceStore.Kind.TIP, "pencil.png")
        assertNotEquals(ref1, ref2)
        assertEquals("pencil.png", ref1.file)
        assertNotEquals("pencil.png", ref2.file)
        assertTrue(ref2.file.startsWith("pencil_"))
        assertTrue(ref2.file.endsWith(".png"))
        assertTrue(ref2.file.contains(ref2.md5.take(8)))

        val file1 = store.fileFor(ref1)
        val file2 = store.fileFor(ref2)
        assertNotNull(file1)
        assertNotNull(file2)
        assertEquals("content_A", file1!!.readText())
        assertEquals("content_B", file2!!.readText())
    }

    @Test
    fun `目录中已有但未建索引的同名文件不被覆盖`() {
        val preExisting = File(brushesDir, "pre.png")
        preExisting.writeBytes("disk_only_content".toByteArray())

        val newBytes = "new_registered_content".toByteArray()
        val ref = store.register(newBytes, ResourceStore.Kind.TIP, "pre.png")

        assertNotEquals("pre.png", ref.file)
        assertTrue(ref.file.startsWith("pre_"))
        assertEquals("disk_only_content", preExisting.readText())
        assertEquals("new_registered_content", store.fileFor(ref)?.readText())
    }

    @Test
    fun `md5与文件字节小写hex一致`() {
        val bytes = "content_for_md5_validation".toByteArray()
        val ref = store.register(bytes, ResourceStore.Kind.TIP, "sample.png")
        val expected = BrushDiagnostics.md5(bytes)

        assertEquals(expected, ref.md5)
        assertEquals(32, ref.md5.length)
        assertTrue(ref.md5.all { it in '0'..'9' || it in 'a'..'f' })

        val diskFile = store.fileFor(ref)
        assertNotNull(diskFile)
        assertEquals(expected, BrushDiagnostics.md5(diskFile!!.readBytes()))
    }

    @Test
    fun `jpg输入转为png落盘且md5按png字节计算`() {
        val jpegBytes = createTestJpeg(16, 16, Color.RED)
        val ref = store.register(jpegBytes, ResourceStore.Kind.PATTERN, "pattern.jpg")

        assertEquals("pattern.png", ref.file)
        assertEquals(ResourceStore.Kind.PATTERN, ref.kind)

        val diskFile = store.fileFor(ref)
        assertNotNull(diskFile)
        val diskBytes = diskFile!!.readBytes()

        // 验证 PNG 头部签名 0x89 0x50 0x4E 0x47
        assertTrue(diskBytes.size >= 8)
        assertEquals(0x89.toByte(), diskBytes[0])
        assertEquals('P'.code.toByte(), diskBytes[1])
        assertEquals('N'.code.toByte(), diskBytes[2])
        assertEquals('G'.code.toByte(), diskBytes[3])

        // md5 必须基于落盘的 png 字节计算，不等于原 jpg 字节 md5
        assertEquals(BrushDiagnostics.md5(diskBytes), ref.md5)
        assertNotEquals(BrushDiagnostics.md5(jpegBytes), ref.md5)
    }

    @Test
    fun `索引可往返重新加载`() {
        val tipBytes = "tip_texture".toByteArray()
        val patJpeg = createTestJpeg(12, 12, Color.GREEN)

        val refTip = store.register(tipBytes, ResourceStore.Kind.TIP, "round.png")
        val refPat = store.register(patJpeg, ResourceStore.Kind.PATTERN, "noise.jpeg")

        // 重新创建实例读取索引
        val reloadedStore = ResourceStore(
            storeRoot = storeRoot,
            brushesDir = brushesDir,
            patternsDir = patternsDir,
            pngTranscoder = testPngTranscoder,
        )

        assertEquals(refTip, reloadedStore.resolve(refTip.md5, ResourceStore.Kind.TIP))
        assertEquals(refPat, reloadedStore.resolve(refPat.md5, ResourceStore.Kind.PATTERN))

        val fileTip = reloadedStore.fileFor(refTip)
        val filePat = reloadedStore.fileFor(refPat)
        assertNotNull(fileTip)
        assertNotNull(filePat)
        assertTrue(fileTip!!.exists())
        assertTrue(filePat!!.exists())
    }

    @Test
    fun `resolve未命中返回null`() {
        assertNull(store.resolve("00000000000000000000000000000000", ResourceStore.Kind.TIP))
        assertNull(store.fileFor("00000000000000000000000000000000", ResourceStore.Kind.TIP))
    }

    @Test
    fun `ResourceStore没有进程级单例`() {
        val cls = ResourceStore::class.java
        assertTrue("不得有 companion object", cls.declaredClasses.none { it.simpleName == "Companion" })
        assertTrue(
            "不得有全局 open/instance/isOpened 入口",
            cls.declaredFields.none { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name in setOf("Companion", "defaultInstance") },
        )
    }

    @Test
    fun `同字节分别作为笔尖和纹理注册时各自落盘`() {
        val bytes = "shared_tip_and_pattern_bytes".toByteArray()
        val tip = store.register(bytes, ResourceStore.Kind.TIP, "shared.png")
        val pat = store.register(bytes, ResourceStore.Kind.PATTERN, "shared.png")

        assertEquals(ResourceStore.Kind.TIP, tip.kind)
        assertEquals(ResourceStore.Kind.PATTERN, pat.kind)
        assertEquals(tip.md5, pat.md5)
        val patFile = File(patternsDir, pat.file)
        assertTrue("纹理必须落在 patterns/", patFile.isFile)
        assertEquals(pat.md5, BrushDiagnostics.md5(patFile.readBytes()))
        assertEquals(tip, store.resolve(tip.md5, ResourceStore.Kind.TIP))
        assertEquals(pat, store.resolve(pat.md5, ResourceStore.Kind.PATTERN))

        val reloaded = ResourceStore(
            storeRoot = storeRoot,
            brushesDir = brushesDir,
            patternsDir = patternsDir,
            pngTranscoder = testPngTranscoder,
        )
        assertEquals(2, reloaded.all().size)
        assertEquals(tip, reloaded.resolve(tip.md5, ResourceStore.Kind.TIP))
        assertEquals(pat, reloaded.resolve(pat.md5, ResourceStore.Kind.PATTERN))
    }

    @Test
    fun `旧版按md5为键的索引仍可读取`() {
        val bytes = "legacy_index_bytes".toByteArray()
        val md5 = BrushDiagnostics.md5(bytes)
        File(patternsDir, "legacy.png").writeBytes(bytes)
        File(storeRoot, "resources.json").writeText(
            """{"schema":1,"resources":{"$md5":{"file":"legacy.png","kind":"PATTERN"}}}""",
        )

        val reloaded = ResourceStore(
            storeRoot = storeRoot,
            brushesDir = brushesDir,
            patternsDir = patternsDir,
            pngTranscoder = testPngTranscoder,
        )
        assertEquals(
            ResourceStore.Ref(md5 = md5, file = "legacy.png", kind = ResourceStore.Kind.PATTERN),
            reloaded.resolve(md5, ResourceStore.Kind.PATTERN),
        )
        assertNull(reloaded.resolve(md5, ResourceStore.Kind.TIP))
    }

    private fun assertDiskMatchesIndex(s: ResourceStore) {
        val refs = s.all()
        assertTrue("索引不应为空", refs.isNotEmpty())
        for (ref in refs) {
            val f = s.fileFor(ref)
            val expectedDir = if (ref.kind == ResourceStore.Kind.TIP) brushesDir else patternsDir
            assertEquals("[${ref.file}] 应落在对应类型目录", expectedDir.canonicalFile, f.parentFile.canonicalFile)
            assertTrue("[${ref.file}] 文件必须存在", f.isFile)
            assertEquals("[${ref.file}] 落盘字节重新哈希必须等于索引 md5", ref.md5, BrushDiagnostics.md5(f.readBytes()))
        }
    }

    @Test
    fun `所有登记资源落盘字节重新哈希与索引md5一致`() {
        store.register("tip_a".toByteArray(), ResourceStore.Kind.TIP, "a.png")
        store.register("tip_a2".toByteArray(), ResourceStore.Kind.TIP, "a.png")
        store.register("pat_a".toByteArray(), ResourceStore.Kind.PATTERN, "a.png")
        store.register("tip_a".toByteArray(), ResourceStore.Kind.PATTERN, "from_tip.png")
        store.register(createTestJpeg(12, 12, Color.GREEN), ResourceStore.Kind.PATTERN, "photo.jpg")
        store.register(createTestJpeg(12, 12, Color.GREEN), ResourceStore.Kind.TIP, "photo.jpeg")
        store.register("tip_a".toByteArray(), ResourceStore.Kind.TIP, "dup.png")

        assertEquals(6, store.all().size)
        assertDiskMatchesIndex(store)

        val reloaded = ResourceStore(
            storeRoot = storeRoot,
            brushesDir = brushesDir,
            patternsDir = patternsDir,
            pngTranscoder = testPngTranscoder,
        )
        assertEquals(6, reloaded.all().size)
        assertDiskMatchesIndex(reloaded)
    }

    @Test
    fun `磁盘文件被外部改写后同字节再次登记另起名且不覆盖改写后的文件`() {
        val original = "original_tip_bytes".toByteArray()
        val ref1 = store.register(original, ResourceStore.Kind.TIP, "x.png")
        val file1 = store.fileFor(ref1)
        file1.writeBytes("tampered_by_someone_else".toByteArray())

        val ref2 = store.register(original, ResourceStore.Kind.TIP, "x.png")

        assertNotEquals("磁盘字节已不匹配，不得复用旧文件", ref1.file, ref2.file)
        assertEquals(ref1.md5, ref2.md5)
        assertEquals("改写后的文件不得被覆盖", "tampered_by_someone_else", file1.readText())
        assertEquals("original_tip_bytes", store.fileFor(ref2).readText())
        assertEquals(ref2, store.resolve(ref1.md5, ResourceStore.Kind.TIP))
        assertDiskMatchesIndex(store)

        val reloaded = ResourceStore(
            storeRoot = storeRoot,
            brushesDir = brushesDir,
            patternsDir = patternsDir,
            pngTranscoder = testPngTranscoder,
        )
        assertEquals(ref2, reloaded.resolve(ref1.md5, ResourceStore.Kind.TIP))
        assertDiskMatchesIndex(reloaded)
    }
}