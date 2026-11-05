/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 四项名字键的偏好（分组/排序/常用/最近，外加快捷栏顺序）改按笔刷 id 存。
 * 一次性、幂等的迁移：旧名字 → id；对不上的不丢，留在 brush_prefs_unmapped 并记日志；旧键原样备份为 *.legacy_names。
 */
class BrushIdPrefsMigrationTest {
    private class MemKv(init: Map<String, Any> = emptyMap()) : BrushIdPrefs.Kv {
        val m = LinkedHashMap<String, Any>(init)
        var commits = 0
        override fun get(key: String): Any? = m[key]
        override fun commit(puts: Map<String, String>, removes: Set<String>) {
            commits++
            removes.forEach { m.remove(it) }
            m.putAll(puts)
        }
    }

    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val logs = ArrayList<String>()

    @Before fun setUp() {
        tmp = Files.createTempDirectory("idprefs").toFile()
        presetDir = File(tmp, "paintoppresets").apply { mkdirs() }
        BrushIdentity.open(File(tmp, "brush_store").apply { mkdirs() })
        fun imp(id: String, name: String, pack: String = "") =
            BrushIdentity.register(BrushIdentity.Entry("imp:$id", name, "kpp", BrushIdentity.Kind.IMPORTED, pack))
        imp("a1", "我的笔")
        imp("a2", "我的笔 (包乙)", "包乙")
        imp("a3", "Soft Pencil")
        imp("a4", "带,逗号的笔")
        BrushIdentity.register(BrushIdentity.Entry("builtin:Basic-5 Size", "Basic-5 Size", "kpp", BrushIdentity.Kind.BUILTIN))
        // 磁盘上有、identity 里没登记的文件（引擎扫描时按 builtin:<名字> 处理）
        File(presetDir, "Disk Only.kpp").writeText("x")
    }

    @After fun tearDown() {
        BrushIdentity.open(File(tmp, "nonexistent-store-reset").apply { mkdirs() })
        tmp.deleteRecursively()
    }

    private fun resolver() = BrushIdPrefs.resolverFor(presetDir)

    private fun legacyGroups() = MemKv(mapOf(
        BrushIdPrefs.GROUPS_LEGACY to """[{"n":"我的笔","g":"素描"},{"n":"我的笔 (包乙)","g":"水彩"},{"n":"Basic-5 Size","g":"素描"},{"n":"已删除的笔","g":"素描"}]""",
        BrushIdPrefs.ORDER_LEGACY to """["Soft Pencil","我的笔 (包乙)","Soft Pencil","Disk Only","我的笔","幽灵笔"]""",
        "custom_groups" to "素描,水彩", // 与笔刷无关的键必须原样保留
    ))

    private fun legacyMain() = MemKv(mapOf(
        BrushIdPrefs.FAV_LEGACY to setOf("Basic-5 Size", "我的笔 (包乙)", "已删除的笔"),
        BrushIdPrefs.RECENT_LEGACY to """["Soft Pencil","我的笔","Soft Pencil","幽灵笔","Basic-5 Size"]""",
        BrushIdPrefs.QUICK_LEGACY to "Soft Pencil,带,逗号的笔,我的笔 (包乙),幽灵笔",
        "brush_size" to "12", // 无关键
    ))

    @Test fun `旧名字全部换成 id 重名带包名的与 builtin 与磁盘文件都对得上`() {
        val g = legacyGroups(); val p = legacyMain()
        val rep = BrushIdPrefs.migrate(g, p, resolver())
        assertTrue(rep.ran)
        val s = BrushIdPrefs.load(g, p)
        assertEquals(mapOf("imp:a1" to "素描", "imp:a2" to "水彩", "builtin:Basic-5 Size" to "素描"), s.groups)
        // 重复项只留第一次出现的位置；"我的笔 (包乙)" 与 "我的笔" 是两个不同的 id
        assertEquals(listOf("imp:a3", "imp:a2", "builtin:Disk Only", "imp:a1"), s.order)
        assertEquals(setOf("builtin:Basic-5 Size", "imp:a2"), s.favorites)
        assertEquals(listOf("imp:a3", "imp:a1", "builtin:Basic-5 Size"), s.recents)
        // 带逗号的名字在旧的逗号拼接里被劈开了，按能对上的最长拼接还原
        assertEquals(listOf("imp:a3", "imp:a4", "imp:a2"), s.quick)
    }

    @Test fun `迁移后旧键不再存在 无关键原样保留 旧值留作 legacy_names 备份`() {
        val g = legacyGroups(); val p = legacyMain()
        BrushIdPrefs.migrate(g, p, resolver())
        for (k in listOf(BrushIdPrefs.GROUPS_LEGACY, BrushIdPrefs.ORDER_LEGACY)) assertNull(k, g.get(k))
        for (k in listOf(BrushIdPrefs.FAV_LEGACY, BrushIdPrefs.RECENT_LEGACY, BrushIdPrefs.QUICK_LEGACY)) assertNull(k, p.get(k))
        assertEquals("素描,水彩", g.get("custom_groups"))
        assertEquals("12", p.get("brush_size"))
        assertTrue((g.get(BrushIdPrefs.GROUPS_LEGACY + BrushIdPrefs.LEGACY_SUFFIX) as String).contains("已删除的笔"))
        assertTrue((p.get(BrushIdPrefs.QUICK_LEGACY + BrushIdPrefs.LEGACY_SUFFIX) as String).contains("幽灵笔"))
        assertTrue(p.get(BrushIdPrefs.FAV_LEGACY + BrushIdPrefs.LEGACY_SUFFIX).toString().contains("已删除的笔"))
    }

    @Test fun `对不上的名字不丢 记在 brush_prefs_unmapped 里`() {
        val g = legacyGroups(); val p = legacyMain()
        val rep = BrushIdPrefs.migrate(g, p, resolver())
        assertEquals(listOf("已删除的笔"), rep.unmapped["groups"])
        assertEquals(listOf("幽灵笔"), rep.unmapped["order"])
        assertEquals(listOf("已删除的笔"), rep.unmapped["favorites"])
        assertEquals(listOf("幽灵笔"), rep.unmapped["recents"])
        assertEquals(listOf("幽灵笔"), rep.unmapped["quick"])
        val saved = JSONObject(g.get(BrushIdPrefs.UNMAPPED) as String)
        assertEquals("已删除的笔", saved.getJSONArray("groups").getJSONObject(0).getString("n"))
        assertEquals("素描", saved.getJSONArray("groups").getJSONObject(0).getString("g"))
        assertEquals("幽灵笔", saved.getJSONArray("order").getString(0))
    }

    @Test fun `幂等 再跑一遍什么都不改 也不再写`() {
        val g = legacyGroups(); val p = legacyMain()
        BrushIdPrefs.migrate(g, p, resolver())
        val g1 = LinkedHashMap(g.m); val p1 = LinkedHashMap(p.m)
        val c = g.commits + p.commits
        val rep2 = BrushIdPrefs.migrate(g, p, resolver())
        assertFalse(rep2.ran)
        assertEquals(g1, g.m); assertEquals(p1, p.m)
        assertEquals(c, g.commits + p.commits)
        assertFalse(BrushIdPrefs.needsMigration(g, p))
    }

    @Test fun `恢复旧备份后再迁移 与新数据合并 新数据在前不被旧数据覆盖`() {
        val g = legacyGroups(); val p = legacyMain()
        BrushIdPrefs.migrate(g, p, resolver())
        // 模拟：已经按 id 用了一阵（常用多了一支、最近换了顺序），之后恢复了一份旧备份（旧键回来了）
        p.commit(mapOf(BrushIdPrefs.FAV_IDS to BrushIdPrefs.encodeList(listOf("imp:a3", "imp:a2"))), emptySet())
        p.commit(mapOf(BrushIdPrefs.FAV_LEGACY to "x"), emptySet())
        p.m[BrushIdPrefs.FAV_LEGACY] = setOf("Basic-5 Size", "Soft Pencil")
        assertTrue(BrushIdPrefs.needsMigration(g, p))
        BrushIdPrefs.migrate(g, p, resolver())
        assertEquals(setOf("imp:a3", "imp:a2", "builtin:Basic-5 Size"), BrushIdPrefs.load(g, p).favorites)
        assertNull(p.get(BrushIdPrefs.FAV_LEGACY))
        assertFalse(BrushIdPrefs.needsMigration(g, p))
    }

    @Test fun `损坏的 json 不崩 原文留在 unmapped 的 unparsed 里`() {
        val g = MemKv(mapOf(BrushIdPrefs.GROUPS_LEGACY to "{not json", BrushIdPrefs.ORDER_LEGACY to """["Soft Pencil"]"""))
        val p = MemKv(mapOf(BrushIdPrefs.RECENT_LEGACY to "[[["))
        val rep = BrushIdPrefs.migrate(g, p, resolver())
        assertTrue(rep.ran)
        val s = BrushIdPrefs.load(g, p)
        assertEquals(listOf("imp:a3"), s.order)
        assertTrue(s.groups.isEmpty() && s.recents.isEmpty())
        val un = JSONObject(g.get(BrushIdPrefs.UNMAPPED) as String).getJSONObject("unparsed")
        assertEquals("{not json", un.getString(BrushIdPrefs.GROUPS_LEGACY))
        assertEquals("[[[", un.getString(BrushIdPrefs.RECENT_LEGACY))
        assertNull(g.get(BrushIdPrefs.GROUPS_LEGACY))
    }

    @Test fun `旧数据里已经是 id 形式的项原样保留 不当成对不上`() {
        val g = MemKv(mapOf(BrushIdPrefs.ORDER_LEGACY to """["builtin:Basic-5 Size","imp:a3","Soft Pencil"]"""))
        val p = MemKv()
        val rep = BrushIdPrefs.migrate(g, p, resolver())
        assertEquals(listOf("builtin:Basic-5 Size", "imp:a3"), BrushIdPrefs.load(g, p).order)
        assertTrue(rep.unmapped.isEmpty())
    }

    @Test fun `没有任何旧键 什么都不做 不写盘`() {
        val g = MemKv(mapOf("custom_groups" to "a")); val p = MemKv()
        assertFalse(BrushIdPrefs.needsMigration(g, p))
        assertFalse(BrushIdPrefs.migrate(g, p, resolver()).ran)
        assertEquals(0, g.commits + p.commits)
    }

    @Test fun `读写只用 id 编码 往返不变`() {
        val g = MemKv(); val p = MemKv()
        g.commit(mapOf(
            BrushIdPrefs.GROUPS_IDS to BrushIdPrefs.encodeGroups(linkedMapOf("imp:a1" to "素描")),
            BrushIdPrefs.ORDER_IDS to BrushIdPrefs.encodeList(listOf("imp:a3", "imp:a1")),
        ), emptySet())
        p.commit(mapOf(
            BrushIdPrefs.FAV_IDS to BrushIdPrefs.encodeList(listOf("imp:a3")),
            BrushIdPrefs.RECENT_IDS to BrushIdPrefs.encodeList(listOf("imp:a1", "imp:a3")),
            BrushIdPrefs.QUICK_IDS to BrushIdPrefs.encodeList(listOf("imp:a4")),
        ), emptySet())
        val s = BrushIdPrefs.load(g, p)
        assertEquals(mapOf("imp:a1" to "素描"), s.groups)
        assertEquals(listOf("imp:a3", "imp:a1"), s.order)
        assertEquals(setOf("imp:a3"), s.favorites)
        assertEquals(listOf("imp:a1", "imp:a3"), s.recents)
        assertEquals(listOf("imp:a4"), s.quick)
        assertEquals("imp:a1", JSONArray(g.get(BrushIdPrefs.GROUPS_IDS) as String).getJSONObject(0).getString("i"))
    }

    @Test fun `改名不影响偏好 因为键是 id`() {
        val g = legacyGroups(); val p = legacyMain()
        BrushIdPrefs.migrate(g, p, resolver())
        assertTrue(BrushIdentity.rename("imp:a3", "Soft Pencil v2"))
        val s = BrushIdPrefs.load(g, p)
        assertEquals("imp:a3", s.order.first())
        assertTrue("imp:a3" in s.recents)
    }
}
