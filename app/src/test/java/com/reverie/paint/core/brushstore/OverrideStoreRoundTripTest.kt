package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OverrideStoreRoundTripTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ovr").toFile()
        BrushOverrideStore.open(root)
        BrushIdentity.open(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `覆盖写盘后重新打开完全相等`() {
        val o = BrushOverrideStore.Overrides(
            controls = mapOf("SPACING" to 0.25, "OPACITY" to 1, "SIZE_PRESSURE" to true, "SIZE_CURVE" to "<params id=\"pressure\"/>"),
            dirty = setOf("SCATTER"),
            rawKeys = mapOf("Foo/Bar" to "3"),
        )
        BrushOverrideStore.replace("builtin:a", o)
        BrushOverrideStore.open(root)
        val back = BrushOverrideStore.get("builtin:a")
        assertEquals(mapOf("SPACING" to 0.25, "OPACITY" to 1.0, "SIZE_PRESSURE" to true, "SIZE_CURVE" to "<params id=\"pressure\"/>"), back.controls)
        assertEquals(setOf("SCATTER"), back.dirty)
        assertEquals(mapOf("Foo/Bar" to "3"), back.rawKeys)
        assertTrue(File(root, "overrides.json").readText().contains("\"schema\": 3"))
    }

    @Test
    fun `清空覆盖即删除条目`() {
        BrushOverrideStore.replace("imp:x", BrushOverrideStore.Overrides(controls = mapOf("FLOW" to 0.5)))
        BrushOverrideStore.clear("imp:x")
        BrushOverrideStore.open(root)
        assertTrue(BrushOverrideStore.get("imp:x").isEmpty())
        assertFalse("imp:x" in BrushOverrideStore.ids())
        BrushOverrideStore.replace("imp:y", BrushOverrideStore.Overrides())
        assertFalse("imp:y" in BrushOverrideStore.ids())
    }

    @Test
    fun `身份表名字与 id 双向往返且改名保持 id`() {
        val id = BrushIdentity.newImportedId()
        BrushIdentity.register(BrushIdentity.Entry(id, "Ink", "kpp", BrushIdentity.Kind.IMPORTED, "pack.zip"))
        BrushIdentity.register(BrushIdentity.Entry("builtin:b", "b", "kpp", BrushIdentity.Kind.BUILTIN))
        BrushIdentity.open(root)
        assertEquals(id, BrushIdentity.idForName("Ink"))
        assertEquals("pack.zip", BrushIdentity.entry(id)!!.pack)
        assertTrue(BrushIdentity.rename(id, "Ink2"))
        assertFalse(BrushIdentity.rename(id, "b"))
        BrushIdentity.open(root)
        assertEquals(id, BrushIdentity.idForName("Ink2"))
        assertEquals(null, BrushIdentity.idForName("Ink"))
    }

    @Test
    fun `撞名生成 名字 加包名 再加序号`() {
        assertEquals("Ink", BrushIdentity.uniqueDisplayName("Ink", "p.zip", setOf("Other")))
        assertEquals("Ink (p)", BrushIdentity.uniqueDisplayName("Ink", "p.zip", setOf("ink")))
        assertEquals("Ink (p) 2", BrushIdentity.uniqueDisplayName("Ink", "p.zip", setOf("Ink", "Ink (p)")))
        assertEquals("Ink (p) 3", BrushIdentity.uniqueDisplayName("Ink", "p.zip", setOf("Ink", "Ink (p)", "Ink (p) 2")))
        assertEquals("imp_abc", BrushIdentity.dirNameFor("imp:abc"))
    }
}
