/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.backup.BackupCategory
import com.reverie.paint.core.backup.BrushBackupMerge
import com.reverie.paint.core.backup.ConflictResolution
import com.reverie.paint.core.backup.LocalBackupEngine
import com.reverie.paint.core.backup.RestoreOptions
import com.reverie.paint.core.brushstore.BrushIdPrefs
import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushImportPipeline
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.KppFixtures
import com.reverie.paint.core.brushstore.PresetSnapshotStore
import com.reverie.paint.core.brushstore.ResourceDirs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 跨设备恢复：导入笔刷（imp:<uuid>）的常用 / 最近 / 快捷栏顺序 / 分组，靠 identity.json 把 id 对回名字。
 * 备份必须带上 identity.json 和 brush_groups，恢复时与本机已有数据合并（本机优先），旧备份（没有这两项）照常能恢复。
 */
class BrushBackupCrossDeviceTest {
    private class MemKv(val m: MutableMap<String, Any?> = LinkedHashMap()) : BrushIdPrefs.Kv {
        override fun get(key: String): Any? = m[key]
        override fun commit(puts: Map<String, String>, removes: Set<String>) {
            removes.forEach { m.remove(it) }
            m.putAll(puts)
        }
    }

    private lateinit var tmp: File
    private val stem = "我的笔"

    @Before fun setUp() {
        tmp = Files.createTempDirectory("xdev").toFile()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
        BrushStoreBootstrap.resetForTest()
    }

    private fun device(name: String): File = File(tmp, name).apply { mkdirs() }

    private fun openStore(filesDir: File) {
        BrushStoreBootstrap.resetForTest()
        ResourceDirs.presets(filesDir).mkdirs()
        BrushStoreBootstrap.openStores(BrushStoreBootstrap.storeRoot(filesDir))
    }

    /** 设备 A：导入了一支笔，并把它放进常用/最近/快捷栏/分组；返回 (备份字节, 笔刷 id) */
    private fun backupFromDeviceA(): Pair<ByteArray, String> {
        val a = device("A")
        openStore(a)
        val presets = ResourceDirs.presets(a)
        File(presets, "$stem.kpp").writeBytes(KppFixtures.bytes(KppFixtures.all.first().first))
        val id = requireNotNull(BrushImportPipeline.adoptFile(stem, presets, "import", pack = "包甲.zip"))
        assertTrue(id.startsWith("imp:"))
        val prefs = mapOf<String, Any?>(
            BrushIdPrefs.FAV_IDS to BrushIdPrefs.encodeList(listOf(id)),
            BrushIdPrefs.RECENT_IDS to BrushIdPrefs.encodeList(listOf(id)),
            BrushIdPrefs.QUICK_IDS to BrushIdPrefs.encodeList(listOf(id)),
        )
        val groups = mapOf<String, Any?>(
            BrushIdPrefs.GROUPS_IDS to BrushIdPrefs.encodeGroups(mapOf(id to "水彩")),
            BrushIdPrefs.ORDER_IDS to BrushIdPrefs.encodeList(listOf(id)),
            "custom_groups" to JSONArray(listOf("水彩")).toString(),
        )
        val out = ByteArrayOutputStream()
        LocalBackupEngine.createBackup(a, prefs, BackupCategory.values().toSet(), out, groupsMap = groups)
        return out.toByteArray() to id
    }

    private class DeviceB(val filesDir: File) {
        val paintPrefs = MemKv()
        val groupPrefs = MemKv()
    }

    /** 模拟 App 里的恢复流程：先写 pending → 引擎恢复（带两个偏好 applier）→ 重开 identity → reconcileDisk */
    private fun restoreOnto(b: DeviceB, bytes: ByteArray): com.reverie.paint.core.backup.RestoreOutcome {
        BrushStoreBootstrap.beginReconcilePending(b.filesDir)
        val outcome = LocalBackupEngine.restoreBackup(
            filesDir = b.filesDir,
            prefApplier = { prefsJson, _, _ ->
                prefsJson?.let { j ->
                    LocalBackupEngine.decodePreferences(j) { k -> b.paintPrefs.m[k] as? String }
                        .forEach { (k, v) -> b.paintPrefs.m[k] = v }
                }
            },
            inputStream = ByteArrayInputStream(bytes),
            options = RestoreOptions(BackupCategory.values().toSet(), brushConflictResolution = ConflictResolution.SKIP_EXISTING),
            groupsApplier = { json ->
                BrushBackupMerge.mergeGroupsPrefs({ k -> b.groupPrefs.m[k] as? String }, json)
                    .forEach { (k, v) -> b.groupPrefs.m[k] = v }
            },
            replaceFile = { s, t -> Files.move(s.toPath(), t.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) },
        )
        if (outcome.restoredBrushIdentities > 0 || BrushIdentity.isOpen()) BrushIdentity.open(BrushStoreBootstrap.storeRoot(b.filesDir))
        BrushStoreBootstrap.reconcileDisk(b.filesDir)
        return outcome
    }

    @Test fun `跨设备恢复后导入笔刷保留原 id，常用 最近 快捷栏 分组都对得上`() {
        val (bytes, id) = backupFromDeviceA()

        // 设备 B：全新设备，本机已经有另一支导入笔和它自己的常用/分组
        val b = DeviceB(device("B"))
        openStore(b.filesDir)
        val localPresets = ResourceDirs.presets(b.filesDir)
        File(localPresets, "本机笔.kpp").writeBytes(KppFixtures.bytes(KppFixtures.all.first().first))
        val localId = requireNotNull(BrushImportPipeline.adoptFile("本机笔", localPresets, "import"))
        b.paintPrefs.m[BrushIdPrefs.FAV_IDS] = BrushIdPrefs.encodeList(listOf(localId))
        b.groupPrefs.m[BrushIdPrefs.GROUPS_IDS] = BrushIdPrefs.encodeGroups(mapOf(localId to "素描"))

        val outcome = restoreOnto(b, bytes)

        // 身份：同一个 id 指向同一个名字；而且是被管理的（有快照），还能编辑
        assertEquals("恢复后 $stem 应保持设备 A 上的 id", id, BrushIdentity.idForName(stem))
        assertTrue("没有快照的话这支笔会变成不受管理", PresetSnapshotStore.has(id))
        assertEquals("本机已有的笔不动", localId, BrushIdentity.idForName("本机笔"))
        assertEquals(1, outcome.restoredBrushIdentities)

        // 偏好：本机的在前，备份的并进来
        val s = BrushIdPrefs.load(b.groupPrefs, b.paintPrefs)
        assertEquals(setOf(localId, id), s.favorites)
        assertEquals(listOf(id), s.recents)
        assertEquals(listOf(id), s.quick)
        assertEquals("水彩", s.groups[id])
        assertEquals("素描", s.groups[localId])
        assertEquals(listOf(id), s.order)
        assertTrue(outcome.restoredBrushGroups)
    }

    @Test fun `旧备份没有 identity 和 brush_groups 也照常恢复，本机数据不变`() {
        val zipBytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"version":1,"categories":["brushes","settings_layout"]}""".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("data/preferences.json"))
                z.write(JSONObject().put("uiOpacity", JSONObject().put("t", "float").put("v", 0.5)).toString().toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("data/brushes/presets/老笔.kpp")); z.write(KppFixtures.bytes(KppFixtures.all.first().first)); z.closeEntry()
            }
        }.toByteArray()
        val b = DeviceB(device("B"))
        openStore(b.filesDir)
        b.paintPrefs.m[BrushIdPrefs.FAV_IDS] = BrushIdPrefs.encodeList(listOf("imp:local"))
        b.groupPrefs.m[BrushIdPrefs.GROUPS_IDS] = BrushIdPrefs.encodeGroups(mapOf("imp:local" to "素描"))

        val outcome = restoreOnto(b, zipBytes)

        assertTrue(outcome.errors.isEmpty())
        assertEquals(1, outcome.restoredBrushes)
        assertEquals(0, outcome.restoredBrushIdentities)
        assertFalse(outcome.restoredBrushGroups)
        assertEquals(0.5f, b.paintPrefs.m["uiOpacity"])
        assertEquals(BrushIdPrefs.encodeList(listOf("imp:local")), b.paintPrefs.m[BrushIdPrefs.FAV_IDS])
        assertEquals(BrushIdPrefs.encodeGroups(mapOf("imp:local" to "素描")), b.groupPrefs.m[BrushIdPrefs.GROUPS_IDS])
        assertNotNull("旧备份里的预设仍按旧办法被登记", BrushIdentity.idForName("老笔"))
    }

    @Test fun `合并 identity：本机优先，撞名的跳过，磁盘上没有预设文件的跳过，内置的不收`() {
        fun entry(id: String, name: String) = JSONObject().put("name", name).put("ext", "kpp")
        val existing = JSONObject().put("schema", 2).put(
            "presets",
            JSONObject().put("imp:L1", entry("imp:L1", "本机笔").put("pack", "本机")).put("builtin:X", entry("builtin:X", "X")),
        ).toString()
        val incoming = JSONObject().put("schema", 2).put(
            "presets",
            JSONObject()
                .put("imp:L1", entry("imp:L1", "改过名的本机笔"))      // 同 id：本机的名字不被覆盖
                .put("imp:N1", entry("imp:N1", "新笔"))                // 新：并入
                .put("imp:N2", entry("imp:N2", "本机笔"))              // 名字撞了本机另一支：跳过
                .put("imp:N3", entry("imp:N3", "没文件的笔"))          // 磁盘上没有 → 跳过
                .put("builtin:Y", entry("builtin:Y", "Y")),           // 内置由出厂清单重建，不收
        ).toString()
        val r = BrushBackupMerge.mergeIdentity(existing, incoming) { name, _ -> name != "没文件的笔" }
        val out = JSONObject(r.json).getJSONObject("presets")
        assertEquals("本机笔", out.getJSONObject("imp:L1").getString("name"))
        assertEquals("新笔", out.getJSONObject("imp:N1").getString("name"))
        assertFalse(out.has("imp:N2"))
        assertFalse(out.has("imp:N3"))
        assertFalse(out.has("builtin:Y"))
        assertTrue("本机原有条目（含内置）原样保留", out.has("builtin:X"))
        assertEquals(1, r.added)
        assertEquals(2, r.skipped)
    }

    @Test fun `合并偏好：本机优先，最近限 16 条，旧版名字键不被碰`() {
        val local = mapOf(
            BrushIdPrefs.GROUPS_IDS to BrushIdPrefs.encodeGroups(mapOf("imp:a" to "本机组")),
            BrushIdPrefs.ORDER_IDS to BrushIdPrefs.encodeList(listOf("imp:a")),
            "custom_groups" to """["本机组"]""",
        )
        val incoming = JSONObject()
            .put(BrushIdPrefs.GROUPS_IDS, BrushIdPrefs.encodeGroups(mapOf("imp:a" to "备份组", "imp:b" to "备份组")))
            .put(BrushIdPrefs.ORDER_IDS, BrushIdPrefs.encodeList(listOf("imp:b", "imp:a")))
            .put("custom_groups", """["备份组","本机组"]""")
            .put("brush_order", "旧名字键必须忽略")
            .toString()
        val puts = BrushBackupMerge.mergeGroupsPrefs({ local[it] }, incoming)
        assertEquals(mapOf("imp:a" to "本机组", "imp:b" to "备份组"), BrushIdPrefs.load(MemKv(puts.toMutableMap<String, Any?>()), MemKv()).groups)
        assertEquals(listOf("imp:a", "imp:b"), BrushIdPrefs.load(MemKv(puts.toMutableMap<String, Any?>()), MemKv()).order)
        assertEquals(listOf("本机组", "备份组"), JSONArray(puts.getValue("custom_groups")).let { a -> (0 until a.length()).map { a.getString(it) } })
        assertNull("旧版名字键不属于这一项", puts["brush_order"])

        val recentLocal = BrushIdPrefs.encodeList((1..10).map { "imp:l$it" })
        val recentIn = BrushIdPrefs.encodeList((1..10).map { "imp:b$it" })
        val merged = BrushIdPrefs.load(MemKv(), MemKv(mutableMapOf(BrushIdPrefs.RECENT_IDS to BrushBackupMerge.mergeIdsValue(BrushIdPrefs.RECENT_IDS, recentLocal, recentIn))))
        assertEquals(BrushIdPrefs.RECENT_MAX, merged.recents.size)
        assertEquals("imp:l1", merged.recents.first())
    }
}
