/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.backup

import com.reverie.paint.core.brushstore.BrushIdPrefs
import com.reverie.paint.core.brushstore.BrushIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 备份恢复时「按笔刷 id 存的数据」怎么并入本机。纯函数，不碰 Android，JVM 单测直接用。
 *
 * 原则：恢复是合并不是覆盖——本机已有的一律保留（同一个 id、同一个键，本机优先），备份里的只补上本机没有的。
 * 这样把备份恢复到「已经在用的设备」也不会冲掉刚做的整理；恢复到全新设备时则等于备份原样。
 * 代价：备份之后在本机取消的常用 / 删掉的分组，恢复后会回来（并集）。
 */
object BrushBackupMerge {
    /** 备份里的 identity.json 只带导入笔（imp:）；内置笔由出厂清单重建。 */
    fun identityFile(filesDir: File): File = File(filesDir, "brush_store/${BrushIdentity.FILE_NAME}")

    /** `brush_groups` 里随备份走的键（其余键是索引 / 迁移标记 / 旧名字键，跟设备绑定，不带）。 */
    val GROUPS_KEYS = listOf(
        BrushIdPrefs.GROUPS_IDS, BrushIdPrefs.ORDER_IDS, "custom_groups", "category_order", "tool_brush_states",
    )

    /** `paint_prefs` 里按 id 存的三项，恢复时并集而不是覆盖。 */
    private val MAIN_ID_KEYS = setOf(BrushIdPrefs.FAV_IDS, BrushIdPrefs.RECENT_IDS, BrushIdPrefs.QUICK_IDS)

    fun isMainIdKey(key: String): Boolean = key in MAIN_ID_KEYS

    // ---------------- identity.json ----------------

    data class IdentityMerge(val json: String, val added: Int, val skipped: Int)

    /** 备份侧：只留导入笔条目；没有文件 / 没有导入笔返回 null（不写这个备份条目）。 */
    fun identityForBackup(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val presets = runCatching { JSONObject(raw).optJSONObject("presets") }.getOrNull() ?: return null
        val out = JSONObject()
        for (id in presets.keys()) if (BrushIdentity.isImported(id)) out.put(id, presets.getJSONObject(id))
        if (out.length() == 0) return null
        return JSONObject().put("schema", 2).put("presets", out).toString(1)
    }

    /**
     * 恢复侧：把备份里的导入笔条目并进本机 identity。
     * - 同 id：本机的不动；
     * - 名字撞了本机另一支笔：跳过（计入 skipped），那支笔的偏好留在备份 id 下不会指向别的笔；
     * - 恢复后磁盘上没有对应预设文件（比如选了 SKIP 或根本没带 BRUSHES）：跳过；
     * - builtin 或不认识的 id：忽略。
     */
    fun mergeIdentity(existing: String?, incoming: String, presetExists: (name: String, ext: String) -> Boolean): IdentityMerge {
        val merged = JSONObject()
        val taken = HashMap<String, String>() // 小写名字 -> id
        runCatching { JSONObject(existing ?: "").optJSONObject("presets") }.getOrNull()?.let { local ->
            for (id in local.keys()) {
                val o = local.getJSONObject(id)
                merged.put(id, o)
                taken[o.optString("name").lowercase()] = id
            }
        }
        val inc = JSONObject(incoming).optJSONObject("presets") ?: JSONObject()
        var added = 0
        var skipped = 0
        for (id in inc.keys()) {
            if (!BrushIdentity.isImported(id) || merged.has(id)) continue
            val o = inc.optJSONObject(id) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            val ext = o.optString("ext", "kpp")
            if (taken.containsKey(name.lowercase()) || !presetExists(name, ext)) {
                skipped++
                continue
            }
            merged.put(id, o)
            taken[name.lowercase()] = id
            added++
        }
        return IdentityMerge(JSONObject().put("schema", 2).put("presets", merged).toString(1), added, skipped)
    }

    // ---------------- 偏好 ----------------

    private fun list(raw: String?): List<String> = runCatching {
        val a = JSONArray(raw ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { v -> v.isNotEmpty() } }
    }.getOrDefault(emptyList())

    /** paint_prefs 里 id 列表键：本机在前、备份补后，去重；最近列表仍限 [BrushIdPrefs.RECENT_MAX]。 */
    fun mergeIdsValue(key: String, existing: String?, incoming: String): String {
        val merged = (list(existing) + list(incoming)).distinct()
        return BrushIdPrefs.encodeList(if (key == BrushIdPrefs.RECENT_IDS) merged.take(BrushIdPrefs.RECENT_MAX) else merged)
    }

    private fun mergeGroupPairs(existing: String?, incoming: String): String {
        val out = LinkedHashMap<String, String>()
        fun add(raw: String?, override: Boolean) = runCatching {
            val a = JSONArray(raw ?: return@runCatching)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("i"); val g = o.optString("g")
                if (id.isNotEmpty() && g.isNotEmpty() && (override || id !in out)) out[id] = g
            }
        }
        add(existing, true)
        add(incoming, false)
        return BrushIdPrefs.encodeGroups(out)
    }

    /** 每个工具槽的「各笔刷记住的大小/不透明度/流量」（pmi，按 id）：本机优先并集；其余槽位字段（索引、滚动位置）跟设备绑定，不带。 */
    private fun mergeToolStates(existing: String?, incoming: String): String {
        val local = runCatching { JSONArray(existing ?: "[]") }.getOrDefault(JSONArray())
        val inc = runCatching { JSONArray(incoming) }.getOrDefault(JSONArray())
        val out = JSONArray()
        val byTool = LinkedHashMap<String, JSONObject>()
        for (i in 0 until local.length()) {
            val o = local.optJSONObject(i) ?: continue
            byTool[o.optString("id")] = o
            out.put(o)
        }
        for (i in 0 until inc.length()) {
            val src = inc.optJSONObject(i) ?: continue
            val tool = src.optString("id")
            val pmi = src.optJSONArray("pmi") ?: continue
            if (tool.isEmpty() || pmi.length() == 0) continue
            val dst = byTool[tool]
            if (dst == null) {
                val fresh = JSONObject().put("id", tool).put("pmi", pmi)
                byTool[tool] = fresh
                out.put(fresh)
                continue
            }
            // 本机槽位还停在旧版「按名字」的 pm 上时不动它（启动迁移会转成 pmi），免得新写的 pmi 盖住它
            if (dst.has("pm") && !dst.has("pmi")) continue
            val have = HashSet<String>()
            val dstPmi = dst.optJSONArray("pmi") ?: JSONArray().also { dst.put("pmi", it) }
            for (j in 0 until dstPmi.length()) dstPmi.optJSONArray(j)?.let { have.add(it.optString(0)) }
            for (j in 0 until pmi.length()) {
                val e = pmi.optJSONArray(j) ?: continue
                if (e.length() >= 4 && have.add(e.optString(0))) dstPmi.put(e)
            }
        }
        return out.toString()
    }

    private fun mergeStringList(existing: String?, incoming: String): String =
        JSONArray((list(existing) + list(incoming)).distinct()).toString()

    /**
     * brush_groups：[incomingJson] 是 `{键: 字符串值}`；返回要写进 brush_groups 的键值（只含白名单键，只含有变化的需要写的）。
     */
    fun mergeGroupsPrefs(existing: (String) -> String?, incomingJson: String): Map<String, String> {
        val inc = JSONObject(incomingJson)
        val puts = LinkedHashMap<String, String>()
        for (key in GROUPS_KEYS) {
            val v = inc.optString(key, "")
            if (v.isEmpty()) continue
            val old = existing(key)
            puts[key] = when (key) {
                BrushIdPrefs.GROUPS_IDS -> mergeGroupPairs(old, v)
                BrushIdPrefs.ORDER_IDS, "custom_groups", "category_order" -> mergeStringList(old, v)
                else -> mergeToolStates(old, v)
            }
        }
        return puts
    }

    /** 备份侧：从 brush_groups 的全部键值里挑出白名单键。没有就返回 null（不写这个备份条目）。 */
    fun groupsForBackup(groupsMap: Map<String, *>): String? {
        val o = JSONObject()
        for (key in GROUPS_KEYS) (groupsMap[key] as? String)?.takeIf { it.isNotEmpty() }?.let { o.put(key, it) }
        return if (o.length() == 0) null else o.toString()
    }

    // ---------------- 排除出厂内置预设与材质 ----------------

    const val FACTORY_RESOURCES_FILE = "factory_resources.json"

    fun saveFactoryResources(
        filesDir: File,
        tips: Collection<String>,
        patterns: Collection<String>,
        presets: Collection<String> = emptyList(),
    ) {
        val storeRoot = File(filesDir, "brush_store")
        storeRoot.mkdirs()
        val resFile = File(storeRoot, FACTORY_RESOURCES_FILE)
        val obj = JSONObject()
            .put("tips", JSONArray(tips.map { it }))
            .put("patterns", JSONArray(patterns.map { it }))
        if (presets.isNotEmpty()) {
            obj.put("presets", JSONArray(presets.map { it }))
        }
        com.reverie.paint.core.brushstore.AtomicFiles.writeText(resFile, obj.toString(1))
    }

    fun collectFactoryResourceNames(filesDir: File): Pair<Set<String>, Set<String>> {
        val storeRoot = File(filesDir, "brush_store")
        val resFile = File(storeRoot, FACTORY_RESOURCES_FILE)
        if (!resFile.isFile) return emptySet<String>() to emptySet<String>()
        return runCatching {
            val obj = JSONObject(resFile.readText())
            val tips = obj.optJSONArray("tips")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
            } ?: emptySet()
            val patterns = obj.optJSONArray("patterns")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
            } ?: emptySet()
            tips to patterns
        }.getOrDefault(emptySet<String>() to emptySet<String>())
    }

    fun collectBuiltinPresetNames(filesDir: File): Set<String> {
        val builtinNames = HashSet<String>()
        // 1. identity.json
        val idFile = identityFile(filesDir)
        if (idFile.isFile) {
            runCatching {
                val presets = JSONObject(idFile.readText()).optJSONObject("presets")
                if (presets != null) {
                    for (id in presets.keys()) {
                        if (BrushIdentity.isBuiltin(id)) {
                            val obj = presets.optJSONObject(id) ?: continue
                            val name = obj.optString("name")
                            val ext = obj.optString("ext", "kpp")
                            if (name.isNotEmpty()) {
                                builtinNames.add("$name.$ext".lowercase())
                                builtinNames.add(name.lowercase())
                            }
                        }
                    }
                }
            }
        }
        // 2. brush_store/snapshots/factory/*/*
        val factoryDir = File(filesDir, "brush_store/snapshots/factory")
        if (factoryDir.isDirectory) {
            factoryDir.listFiles()?.filter { it.isDirectory }?.forEach { verDir ->
                verDir.list()?.forEach { fn ->
                    builtinNames.add(fn.lowercase())
                    builtinNames.add(fn.substringBeforeLast('.').lowercase())
                }
            }
        }
        // 3. factory_resources.json
        val storeRoot = File(filesDir, "brush_store")
        val resFile = File(storeRoot, FACTORY_RESOURCES_FILE)
        if (resFile.isFile) {
            runCatching {
                val arr = JSONObject(resFile.readText()).optJSONArray("presets")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val fn = arr.optString(i)
                        if (fn.isNotEmpty()) {
                            builtinNames.add(fn.lowercase())
                            builtinNames.add(fn.substringBeforeLast('.').lowercase())
                        }
                    }
                }
            }
        }
        return builtinNames
    }

    fun isBuiltinPreset(fileName: String, builtinNames: Set<String>): Boolean {
        if (builtinNames.isEmpty()) return false
        val lower = fileName.lowercase()
        val stem = fileName.substringBeforeLast('.').lowercase()
        return lower in builtinNames || stem in builtinNames
    }
}

