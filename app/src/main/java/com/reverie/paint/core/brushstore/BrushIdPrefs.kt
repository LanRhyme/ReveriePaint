/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔刷偏好（分组 / 排序 / 常用 / 最近 / 快捷栏顺序）按笔刷 id 存取，只此一处读写。
 *
 * 旧版本按「显示名」存这五项；改名、重名（"名字 (包名)"）、导入同名都会让键失效。
 * [migrate] 是一次性、幂等的转换：旧名字 → id（经 [BrushIdentity]，磁盘上有文件但没登记的按
 * [BrushStoreVm.idForScanned] 同样的规则落到 `builtin:<名字>`）。
 *  - 旧键整体备份为 `<旧键>.legacy_names` 后删除，所以再跑一遍什么都不做；
 *  - 对不上的名字不丢：记日志（`prefs_unmapped`）并存进 [UNMAPPED]；
 *  - 已经有 id 数据时（恢复了一份旧备份）与之合并：现有数据在前，旧数据只补没有的。
 *
 * 存储是 SharedPreferences 两个文件（brush_groups / paint_prefs），这里经 [Kv] 抽象，JVM 单测不需要 Android。
 */
object BrushIdPrefs {
    // brush_groups 文件
    const val GROUPS_LEGACY = "user_groups"
    const val ORDER_LEGACY = "brush_order"
    const val GROUPS_IDS = "user_groups_ids"
    const val ORDER_IDS = "brush_order_ids"
    const val UNMAPPED = "brush_prefs_unmapped"

    // paint_prefs 文件
    const val FAV_LEGACY = "brush_favorite_names"
    const val RECENT_LEGACY = "brush_recent_names"
    const val QUICK_LEGACY = "quick_brush_order"
    const val FAV_IDS = "brush_favorite_ids"
    const val RECENT_IDS = "brush_recent_ids"
    const val QUICK_IDS = "quick_brush_order_ids"

    const val LEGACY_SUFFIX = ".legacy_names"
    const val RECENT_MAX = 16

    interface Kv {
        /** 原始值：String / Set<String> / null（不抛 ClassCastException） */
        fun get(key: String): Any?

        /** 一次原子提交：先删后写 */
        fun commit(puts: Map<String, String>, removes: Set<String>)
    }

    class SharedPrefsKv(private val prefs: android.content.SharedPreferences) : Kv {
        override fun get(key: String): Any? = if (prefs.contains(key)) prefs.all[key] else null
        override fun commit(puts: Map<String, String>, removes: Set<String>) {
            val e = prefs.edit()
            removes.forEach { e.remove(it) }
            puts.forEach { (k, v) -> e.putString(k, v) }
            e.commit()
        }
    }

    data class State(
        val groups: Map<String, String>,
        val order: List<String>,
        val favorites: Set<String>,
        val recents: List<String>,
        val quick: List<String>,
    )

    /** [unmapped] 的键：groups / order / favorites / recents / quick（值是对不上的旧名字） */
    data class Report(val ran: Boolean, val mapped: Map<String, Int>, val unmapped: Map<String, List<String>>)

    /**
     * 旧名字 → id。顺序：已经是登记过的 id → 精确显示名 → 磁盘上同名预设文件（builtin:<名字>，与扫描规则一致）。
     * 都不是返回 null（调用方记日志并留存）。
     */
    fun resolverFor(presetDir: File?): (String) -> String? = { name ->
        when {
            BrushIdentity.entry(name) != null -> name
            BrushIdentity.idForName(name) != null -> BrushIdentity.idForName(name)
            presetDir != null && BrushImportPipeline.PRESET_EXTS.any { File(presetDir, "$name.$it").isFile } ->
                BrushIdentity.builtinId(name)
            else -> null
        }
    }

    // ---- 编码 ----
    fun encodeList(ids: Collection<String>): String = JSONArray().also { a -> ids.forEach { a.put(it) } }.toString()

    fun encodeGroups(groups: Map<String, String>): String =
        JSONArray().also { a -> groups.forEach { (id, g) -> a.put(JSONObject().put("i", id).put("g", g)) } }.toString()

    private fun decodeList(raw: Any?): List<String>? {
        val s = raw as? String ?: return if (raw == null) emptyList() else null
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { v -> v.isNotEmpty() } }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePairs(raw: Any?, nameKey: String): List<Pair<String, String>>? {
        val s = raw as? String ?: return if (raw == null) emptyList() else null
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString(nameKey, ""); val g = o.optString("g", "")
                if (k.isNotEmpty() && g.isNotEmpty()) k to g else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- 读 ----
    fun load(groupsKv: Kv, mainKv: Kv): State = State(
        groups = decodePairs(groupsKv.get(GROUPS_IDS), "i").orEmpty().toMap(LinkedHashMap()),
        order = decodeList(groupsKv.get(ORDER_IDS)).orEmpty().distinct(),
        favorites = LinkedHashSet(decodeList(mainKv.get(FAV_IDS)).orEmpty()),
        recents = decodeList(mainKv.get(RECENT_IDS)).orEmpty().distinct().take(RECENT_MAX),
        quick = decodeList(mainKv.get(QUICK_IDS)).orEmpty().distinct(),
    )

    private val groupsLegacyKeys = listOf(GROUPS_LEGACY, ORDER_LEGACY)
    private val mainLegacyKeys = listOf(FAV_LEGACY, RECENT_LEGACY, QUICK_LEGACY)

    fun needsMigration(groupsKv: Kv, mainKv: Kv): Boolean =
        groupsLegacyKeys.any { groupsKv.get(it) != null } || mainLegacyKeys.any { mainKv.get(it) != null }

    /** 快捷栏旧格式是逗号拼接：名字里带逗号会被劈开，按「能对上的最长拼接」还原。 */
    private fun splitQuick(raw: String, resolve: (String) -> String?): List<String> {
        val tokens = raw.split(',').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            var took = 1
            for (n in minOf(4, tokens.size - i) downTo 2) {
                if (resolve(tokens.subList(i, i + n).joinToString(",")) != null) { took = n; break }
            }
            out += tokens.subList(i, i + took).joinToString(",")
            i += took
        }
        return out
    }

    private fun legacyText(raw: Any?): String? = when (raw) {
        null -> null
        is String -> raw
        is Set<*> -> JSONArray(raw.map { it.toString() }).toString()
        else -> raw.toString()
    }

    fun migrate(
        groupsKv: Kv,
        mainKv: Kv,
        resolve: (String) -> String?,
        log: (String, Array<out Pair<String, Any?>>) -> Unit = { e, kv -> BrushDiagnostics.log(e, *kv) },
    ): Report {
        if (!needsMigration(groupsKv, mainKv)) return Report(false, emptyMap(), emptyMap())

        val unmapped = LinkedHashMap<String, MutableList<String>>()
        val unparsed = LinkedHashMap<String, String>()
        val mapped = LinkedHashMap<String, Int>()
        val unmappedGroupPairs = ArrayList<Pair<String, String>>()
        fun miss(pref: String, name: String) {
            unmapped.getOrPut(pref) { ArrayList() }.add(name)
            log("prefs_unmapped", arrayOf("pref" to pref, "name" to name))
        }
        fun mapNames(pref: String, names: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (n in names) { val id = resolve(n); if (id != null) out += id else miss(pref, n) }
            mapped[pref] = out.size
            return out.toList()
        }

        val cur = load(groupsKv, mainKv)
        val gPuts = LinkedHashMap<String, String>(); val gRem = LinkedHashSet<String>()
        val mPuts = LinkedHashMap<String, String>(); val mRem = LinkedHashSet<String>()

        fun backup(kv: Kv, key: String, raw: Any?, puts: MutableMap<String, String>, rem: MutableSet<String>) {
            legacyText(raw)?.let { puts[key + LEGACY_SUFFIX] = it }
            rem += key
        }

        // 分组
        groupsKv.get(GROUPS_LEGACY)?.let { raw ->
            val pairs = decodePairs(raw, "n")
            if (pairs == null) {
                unparsed[GROUPS_LEGACY] = legacyText(raw).orEmpty()
                log("prefs_unparsed", arrayOf("pref" to GROUPS_LEGACY))
            } else {
                val merged = LinkedHashMap(cur.groups)
                var n = 0
                for ((name, g) in pairs) {
                    val id = resolve(name)
                    if (id == null) {
                        miss("groups", name)
                        unmappedGroupPairs += name to g
                    } else {
                        if (!merged.containsKey(id)) merged[id] = g
                        n++
                    }
                }
                mapped["groups"] = n
                gPuts[GROUPS_IDS] = encodeGroups(merged)
            }
            backup(groupsKv, GROUPS_LEGACY, raw, gPuts, gRem)
        }
        groupsKv.get(ORDER_LEGACY)?.let { raw ->
            val names = decodeList(raw)
            if (names == null) {
                unparsed[ORDER_LEGACY] = legacyText(raw).orEmpty()
                log("prefs_unparsed", arrayOf("pref" to ORDER_LEGACY))
            } else {
                gPuts[ORDER_IDS] = encodeList((cur.order + mapNames("order", names)).distinct())
            }
            backup(groupsKv, ORDER_LEGACY, raw, gPuts, gRem)
        }
        // 常用 / 最近 / 快捷栏
        mainKv.get(FAV_LEGACY)?.let { raw ->
            val names = when (raw) {
                is Set<*> -> raw.map { it.toString() }.sorted()
                else -> decodeList(raw)
            }
            if (names == null) {
                unparsed[FAV_LEGACY] = legacyText(raw).orEmpty()
            } else {
                mPuts[FAV_IDS] = encodeList(LinkedHashSet(cur.favorites).also { it.addAll(mapNames("favorites", names)) })
            }
            backup(mainKv, FAV_LEGACY, raw, mPuts, mRem)
        }
        mainKv.get(RECENT_LEGACY)?.let { raw ->
            val names = decodeList(raw)
            if (names == null) {
                unparsed[RECENT_LEGACY] = legacyText(raw).orEmpty()
            } else {
                mPuts[RECENT_IDS] = encodeList((cur.recents + mapNames("recents", names)).distinct().take(RECENT_MAX))
            }
            backup(mainKv, RECENT_LEGACY, raw, mPuts, mRem)
        }
        mainKv.get(QUICK_LEGACY)?.let { raw ->
            val text = raw as? String
            if (text == null) {
                unparsed[QUICK_LEGACY] = legacyText(raw).orEmpty()
            } else {
                mPuts[QUICK_IDS] = encodeList((cur.quick + mapNames("quick", splitQuick(text, resolve))).distinct())
            }
            backup(mainKv, QUICK_LEGACY, raw, mPuts, mRem)
        }

        // 对不上的 / 读不出的：合并进已有的 unmapped 记录（不覆盖、不丢）
        if (unmapped.isNotEmpty() || unparsed.isNotEmpty()) {
            val o = try { JSONObject(groupsKv.get(UNMAPPED) as? String ?: "{}") } catch (_: Exception) { JSONObject() }
            fun arr(key: String) = o.optJSONArray(key) ?: JSONArray().also { o.put(key, it) }
            for ((pref, names) in unmapped) {
                if (pref == "groups") continue
                val a = arr(pref)
                val have = (0 until a.length()).map { a.optString(it) }.toSet()
                names.filter { it !in have }.forEach { a.put(it) }
            }
            if (unmappedGroupPairs.isNotEmpty()) {
                val a = arr("groups")
                val have = (0 until a.length()).map { a.optJSONObject(it)?.optString("n") }.toSet()
                unmappedGroupPairs.filter { it.first !in have }.forEach { (n, g) -> a.put(JSONObject().put("n", n).put("g", g)) }
            }
            if (unparsed.isNotEmpty()) {
                val u = o.optJSONObject("unparsed") ?: JSONObject().also { o.put("unparsed", it) }
                unparsed.forEach { (k, v) -> u.put(k, v) }
            }
            gPuts[UNMAPPED] = o.toString()
        }

        // 先写 brush_groups 再写 paint_prefs；两个文件不在一个事务里，中途崩溃时剩下的旧键下次启动继续迁（幂等）
        if (gPuts.isNotEmpty() || gRem.isNotEmpty()) groupsKv.commit(gPuts, gRem)
        if (mPuts.isNotEmpty() || mRem.isNotEmpty()) mainKv.commit(mPuts, mRem)
        log(
            "prefs_migrated",
            arrayOf(
                "mapped" to mapped.entries.joinToString(",") { "${it.key}=${it.value}" },
                "unmapped" to unmapped.entries.joinToString(",") { "${it.key}=${it.value.size}" },
                "unparsed" to unparsed.keys.joinToString(","),
            ),
        )
        return Report(true, mapped, unmapped)
    }
}
