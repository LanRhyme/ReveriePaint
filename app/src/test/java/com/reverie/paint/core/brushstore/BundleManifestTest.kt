package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** bundle 的 manifest 标签解析在管线里（原先内联在 PaintViewModelBrush 导入函数里，JVM 测不到） */
class BundleManifestTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("bundlemf").toFile()
        BrushStoreBootstrap.resetForTest()
    }

    @After fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun manifest(vararg entries: Pair<String, String?>): ByteArray {
        val body = entries.joinToString("") { (path, tag) ->
            """<manifest:file-entry manifest:media-type="paintoppresets" manifest:full-path="$path" manifest:md5sum="x">""" +
                (if (tag != null) "<manifest:tags><manifest:tag>$tag</manifest:tag></manifest:tags>" else "") +
                "</manifest:file-entry>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">$body</manifest:manifest>""".toByteArray()
    }

    private val stems by lazy { KppFixtures.all.map { it.first }.take(3) }

    @Test
    fun manifestTagsAttachToPresets_includingNestedBundles() {
        val (a, b, c) = stems
        val inner = zip(
            "paintoppresets/$c.kpp" to KppFixtures.bytes(c),
            "META-INF/manifest.xml" to manifest("paintoppresets/$c.kpp" to "内层标签"),
        )
        val outer = zip(
            "paintoppresets/$a.kpp" to KppFixtures.bytes(a),
            "paintoppresets/$b.kpp" to KppFixtures.bytes(b),
            "inner.bundle" to inner,
            "META-INF/manifest.xml" to manifest("paintoppresets/$a.kpp" to "水彩", "paintoppresets/$b.kpp" to null),
        )
        val p = BrushImportPipeline.parseArchive(outer, "P.bundle")
        assertEquals(mapOf(a to "水彩", b to null, c to "内层标签"), p.presets.associate { it.wantedName to it.tag })
    }

    @Test
    fun malformedManifestDoesNotBlockImport() {
        val (a) = stems
        val p = BrushImportPipeline.parseArchive(
            zip("paintoppresets/$a.kpp" to KppFixtures.bytes(a), "META-INF/manifest.xml" to "<not xml".toByteArray()), "P.bundle",
        )
        assertEquals(1, p.presets.size)
        assertNull(p.presets[0].tag)
    }

    @Test
    fun doctypeInManifestIsIgnored() {
        val (a) = stems
        val evil = """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/passwd">]><m><file-entry full-path="paintoppresets/$a.kpp"><tag>&x;</tag></file-entry></m>""".toByteArray()
        val p = BrushImportPipeline.parseArchive(zip("paintoppresets/$a.kpp" to KppFixtures.bytes(a), "META-INF/manifest.xml" to evil), "P.bundle")
        assertNull(p.presets[0].tag)
    }
}
