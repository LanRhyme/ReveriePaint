package com.reverie.paint.core.brushstore

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 笔刷稳定身份（W-02）。
 *
 * - 内置：`builtin:<assets 里的文件名去扩展名>`
 * - 导入：`imp:<uuid>`
 *
 * 引擎按 paintoppresets/ 里的文件名（去扩展名）识别预设，UI 也以这个名字展示；
 * 这里维护 名字 ↔ id 的双向表，落盘为 `brush_store/identity.json`：
 * `{schema:2, presets:{id:{name, ext, kind, pack}}}`。
 */
object BrushIdentity {
    const val BUILTIN_PREFIX = "builtin:"
    const val IMPORTED_PREFIX = "imp:"
    const val FILE_NAME = "identity.json"

    enum class Kind { BUILTIN, IMPORTED }

    data class Entry(
        val id: String,
        val name: String,
        val ext: String,
        val kind: Kind,
        /** 导入来源包名（zip/abr/bundle 文件名），内置为空 */
        val pack: String = "",
    )

    private val lock = Any()
    private var root: File? = null
    private val byId = LinkedHashMap<String, Entry>()
    private val byName = HashMap<String, String>()

    fun builtinId(name: String): String = BUILTIN_PREFIX + name

    fun newImportedId(): String = IMPORTED_PREFIX + UUID.randomUUID().toString()

    fun isBuiltin(id: String): Boolean = id.startsWith(BUILTIN_PREFIX)

    fun isImported(id: String): Boolean = id.startsWith(IMPORTED_PREFIX)

    /** 目录名：':' 与路径分隔符换成 '_' */
    fun dirNameFor(id: String): String = id.replace(':', '_').replace('/', '_').replace('\\', '_')

    /**
     * 撞名时生成 "名字 (包名)"，仍撞则 "名字 (包名) 2"、"名字 (包名) 3"…
     * 不撞直接返回 [base]。[taken] 按大小写不敏感比较（Android 外部存储与部分 FS 大小写不敏感）。
     */
    fun uniqueDisplayName(base: String, pack: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (base.lowercase() !in lower) return base
        val packLabel = pack.substringBeforeLast('.').ifBlank { "import" }
        val first = "$base ($packLabel)"
        if (first.lowercase() !in lower) return first
        var n = 2
        while ("$first $n".lowercase() in lower) n++
        return "$first $n"
    }

    fun open(storeRoot: File) {
        AtomicFiles.flushPending()
        synchronized(lock) {
            root = storeRoot
            byId.clear()
            byName.clear()
            val f = File(storeRoot, FILE_NAME)
            if (!f.exists()) return
            try {
                val presets = JSONObject(f.readText()).optJSONObject("presets") ?: return
                for (id in presets.keys()) {
                    val o = presets.getJSONObject(id)
                    val e = Entry(
                        id = id,
                        name = o.getString("name"),
                        ext = o.optString("ext", "kpp"),
                        kind = if (isBuiltin(id)) Kind.BUILTIN else Kind.IMPORTED,
                        pack = o.optString("pack", ""),
                    )
                    byId[id] = e
                    byName[e.name] = id
                }
            } catch (t: Throwable) {
                BrushDiagnostics.log("identityLoadFailed", "err" to t.javaClass.simpleName)
            }
        }
    }

    fun isOpen(): Boolean = root != null

    fun idForName(name: String): String? = synchronized(lock) { byName[name] }

    fun entry(id: String): Entry? = synchronized(lock) { byId[id] }

    fun entryForName(name: String): Entry? = synchronized(lock) { byName[name]?.let { byId[it] } }

    fun all(): List<Entry> = synchronized(lock) { byId.values.toList() }

    fun names(): Set<String> = synchronized(lock) { byName.keys.toSet() }

    fun register(entry: Entry) {
        synchronized(lock) {
            byId[entry.id]?.let { byName.remove(it.name) }
            byName[entry.name]?.let { if (it != entry.id) byId.remove(it) }
            byId[entry.id] = entry
            byName[entry.name] = entry.id
            persistLocked()
        }
    }

    /** 批量注册（只落盘一次）；内置同步时用 */
    fun registerAll(entries: Collection<Entry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            for (entry in entries) {
                byId[entry.id]?.let { byName.remove(it.name) }
                byName[entry.name]?.let { if (it != entry.id) byId.remove(it) }
                byId[entry.id] = entry
                byName[entry.name] = entry.id
            }
            persistLocked()
        }
    }

    fun rename(id: String, newName: String): Boolean {
        synchronized(lock) {
            val e = byId[id] ?: return false
            if (byName[newName]?.let { it != id } == true) return false
            byName.remove(e.name)
            val ne = e.copy(name = newName)
            byId[id] = ne
            byName[newName] = id
            persistLocked()
            return true
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val e = byId.remove(id) ?: return
            byName.remove(e.name)
            persistLocked()
        }
    }

    private fun persistLocked() {
        val r = root ?: return
        val presets = JSONObject()
        for (e in byId.values) {
            presets.put(
                e.id,
                JSONObject().put("name", e.name).put("ext", e.ext).apply { if (e.pack.isNotEmpty()) put("pack", e.pack) },
            )
        }
        val text = JSONObject().put("schema", 2).put("presets", presets).toString(1)
        AtomicFiles.writeTextAsync(File(r, FILE_NAME), text)
    }
}
