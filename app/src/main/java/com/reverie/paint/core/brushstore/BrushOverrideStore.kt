package com.reverie.paint.core.brushstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 稀疏覆盖（W-02，D-1：按控件存，rawKeys 兜底）。
 *
 * `brush_store/overrides.json`：
 * ```
 * {schema:3, presets:{<id>:{controls:{<ControlId>:<JSON 基本值>}, dirty:[<ControlId>], rawKeys:{<kpp 参数名>:<值>}}}}
 * ```
 * - controls：用户改过的控件值；曲线以传感器 XML 字符串存。
 * - dirty：迁移时无法确认的控件（K5 等），仅用于诊断展示，不影响生成。
 * - rawKeys：无法映射到控件的 kpp 参数（迁移兜底）；生成时按原样写入 param。
 *
 * 没有条目 == 与快照完全一致。
 */
object BrushOverrideStore {
    const val FILE_NAME = "overrides.json"
    const val SCHEMA = 3

    data class Overrides(
        val controls: Map<String, Any> = emptyMap(),
        val dirty: Set<String> = emptySet(),
        val rawKeys: Map<String, String> = emptyMap(),
    ) {
        fun isEmpty(): Boolean = controls.isEmpty() && rawKeys.isEmpty()
    }

    private val lock = Any()
    private var root: File? = null
    private val map = LinkedHashMap<String, Overrides>()

    fun open(storeRoot: File) {
        synchronized(lock) {
            root = storeRoot
            map.clear()
            val f = File(storeRoot, FILE_NAME)
            if (!f.exists()) return
            try {
                val presets = JSONObject(f.readText()).optJSONObject("presets") ?: return
                for (id in presets.keys()) map[id] = fromJson(presets.getJSONObject(id))
            } catch (t: Throwable) {
                // 损坏时保留原文件供取证，不覆盖
                val bad = File(storeRoot, "$FILE_NAME.corrupt.${System.currentTimeMillis()}")
                f.copyTo(bad, overwrite = true)
                BrushDiagnostics.log("overridesLoadFailed", "err" to t.javaClass.simpleName, "kept" to bad.name)
            }
        }
    }

    fun get(id: String): Overrides = synchronized(lock) { map[id] ?: Overrides() }

    fun ids(): Set<String> = synchronized(lock) { map.keys.toSet() }

    /** 整体替换某预设的覆盖；空覆盖等同于 [clear] */
    fun replace(id: String, o: Overrides) {
        synchronized(lock) {
            if (o.isEmpty() && o.dirty.isEmpty()) map.remove(id) else map[id] = o.copy(controls = o.controls.mapValues { normalize(it.value) })
            persistLocked()
        }
    }

    fun clear(id: String) {
        synchronized(lock) {
            if (map.remove(id) != null) persistLocked()
        }
    }

    /** 复制覆盖（复制笔刷用） */
    fun copy(fromId: String, toId: String) {
        synchronized(lock) {
            map[fromId]?.let { map[toId] = it; persistLocked() }
        }
    }

    fun toJson(o: Overrides): JSONObject {
        val controls = JSONObject()
        for ((k, v) in o.controls.toSortedMap()) controls.put(k, v)
        val raw = JSONObject()
        for ((k, v) in o.rawKeys.toSortedMap()) raw.put(k, v)
        return JSONObject()
            .put("controls", controls)
            .put("dirty", JSONArray(o.dirty.sorted()))
            .put("rawKeys", raw)
    }

    fun fromJson(j: JSONObject): Overrides {
        val controls = LinkedHashMap<String, Any>()
        j.optJSONObject("controls")?.let { c -> for (k in c.keys()) controls[k] = normalize(c.get(k)) }
        val dirty = LinkedHashSet<String>()
        j.optJSONArray("dirty")?.let { a -> for (i in 0 until a.length()) dirty.add(a.getString(i)) }
        val raw = LinkedHashMap<String, String>()
        j.optJSONObject("rawKeys")?.let { r -> for (k in r.keys()) raw[k] = r.get(k).toString() }
        return Overrides(controls, dirty, raw)
    }

    /** JSON 数字统一成 Double / Boolean / String，保证读写往返相等 */
    fun normalize(v: Any): Any = when (v) {
        is Boolean -> v
        is Number -> v.toDouble()
        else -> v.toString()
    }

    private fun persistLocked() {
        val r = root ?: return
        val presets = JSONObject()
        for ((id, o) in map) presets.put(id, toJson(o))
        AtomicFiles.writeText(File(r, FILE_NAME), JSONObject().put("schema", SCHEMA).put("presets", presets).toString(1))
    }
}
