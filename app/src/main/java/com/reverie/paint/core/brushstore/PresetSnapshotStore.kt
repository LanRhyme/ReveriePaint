package com.reverie.paint.core.brushstore

import org.json.JSONObject
import java.io.File

/**
 * 不可变预设快照（W-02）。
 *
 * ```
 * brush_store/snapshots/
 *   factory/<manifestMd5>/<name>.kpp|.myb      内置：assets 原样字节
 *   factory/CURRENT                            当前出厂版本号（manifest md5）
 *   imported/<id 目录名>/preset.<ext>          导入：转换完成那一刻的字节
 *   imported/<id 目录名>/source.json           来源（包名、原始文件名、ABR 源与转换参数…）
 * ```
 *
 * 快照写入后设为只读；同一 id 再次写入会被拒绝（[SnapshotExistsException]），
 * 只有 [BuiltinSnapshotMigrator] 换出厂版本时会整体换目录。
 */
object PresetSnapshotStore {
    const val DIR = "snapshots"
    const val FACTORY = "factory"
    const val IMPORTED = "imported"
    const val RESTORED = "restored"
    const val CURRENT_FILE = "CURRENT"

    class SnapshotExistsException(id: String) : IllegalStateException("snapshot already exists: $id")

    private val lock = Any()
    private var root: File? = null

    fun open(storeRoot: File) {
        synchronized(lock) {
            root = storeRoot
            File(storeRoot, "$DIR/$FACTORY").mkdirs()
            File(storeRoot, "$DIR/$IMPORTED").mkdirs()
            File(storeRoot, "$DIR/$RESTORED").mkdirs()
        }
    }

    private fun requireRoot(): File = root ?: throw IllegalStateException("PresetSnapshotStore not opened")

    fun factoryRoot(): File = File(requireRoot(), "$DIR/$FACTORY")

    fun currentFactoryVersion(): String? =
        File(factoryRoot(), CURRENT_FILE).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun factoryDir(version: String): File = File(factoryRoot(), version)

    /** 写入一个出厂版本的全部快照（目录先在 .partial 里写好再 rename，保证要么完整要么不存在） */
    fun installFactory(version: String, files: Map<String, ByteArray>) {
        synchronized(lock) {
            val dest = factoryDir(version)
            if (dest.exists()) return
            val partial = File(factoryRoot(), "$version.partial")
            partial.deleteRecursively()
            partial.mkdirs()
            for ((fileName, bytes) in files) {
                val f = File(partial, fileName)
                f.writeBytes(bytes)
                f.setReadOnly()
            }
            if (!partial.renameTo(dest)) throw java.io.IOException("factory install rename failed")
        }
    }

    fun setCurrentFactoryVersion(version: String) {
        synchronized(lock) { AtomicFiles.writeText(File(factoryRoot(), CURRENT_FILE), version) }
    }

    /** 删除不再引用的旧出厂版本 */
    fun pruneFactory(keep: Set<String>) {
        synchronized(lock) {
            factoryRoot().listFiles()?.forEach { f ->
                if (f.isDirectory && f.name !in keep) {
                    f.walkBottomUp().forEach { it.setWritable(true) }
                    f.deleteRecursively()
                }
            }
        }
    }

    fun factoryFile(name: String, version: String? = currentFactoryVersion()): File? {
        val v = version ?: return null
        val dir = factoryDir(v)
        for (ext in BrushImportPipeline.PRESET_EXTS) {
            val f = File(dir, "$name.$ext")
            if (f.exists()) return f
        }
        return null
    }

    fun importedDir(id: String): File = File(requireRoot(), "$DIR/$IMPORTED/${BrushIdentity.dirNameFor(id)}")

    fun putImported(id: String, ext: String, bytes: ByteArray, source: JSONObject) {
        require(BrushIdentity.isImported(id)) { "not an imported id: $id" }
        synchronized(lock) {
            val dir = importedDir(id)
            if (dir.exists() && dir.listFiles()?.any { it.name.startsWith("preset.") } == true) {
                throw SnapshotExistsException(id)
            }
            dir.mkdirs()
            val f = File(dir, "preset.$ext")
            AtomicFiles.write(f, bytes)
            f.setReadOnly()
            val s = File(dir, "source.json")
            AtomicFiles.writeText(s, source.toString(1))
            s.setReadOnly()
        }
    }

    fun removeImported(id: String) {
        synchronized(lock) {
            val dir = importedDir(id)
            dir.walkBottomUp().forEach { it.setWritable(true) }
            dir.deleteRecursively()
        }
    }

    fun importedFile(id: String): File? =
        importedDir(id).listFiles()?.firstOrNull { it.isFile && it.name.startsWith("preset.") }

    /** 导入快照旁的 source.json（内置/还原的没有）；基线缓存的指纹要用它的 路径|大小|mtime */
    fun sourceFile(id: String): File? =
        if (BrushIdentity.isImported(id)) File(importedDir(id), "source.json").takeIf { it.exists() } else null

    fun source(id: String): JSONObject? {
        val f = sourceFile(id) ?: return null
        return try {
            JSONObject(f.readText())
        } catch (t: Exception) {
            BrushDiagnostics.log("source_json_unreadable", "id" to id, "err" to t.javaClass.simpleName)
            null
        }
    }

    fun restoredDir(id: String): File = File(requireRoot(), "$DIR/$RESTORED/${BrushIdentity.dirNameFor(id)}")

    fun restoredFile(id: String): File? =
        restoredDir(id).listFiles()?.firstOrNull { it.isFile && it.name.startsWith("preset.") }

    fun removeRestored(id: String) {
        synchronized(lock) {
            val dir = restoredDir(id)
            if (dir.exists()) {
                dir.walkBottomUp().forEach { it.setWritable(true) }
                dir.deleteRecursively()
            }
        }
    }

    /**
     * 磁盘对账（reconcileDisk）专用：以恢复文件为准替换快照。
     * - 出厂预设写到 brush_store/snapshots/restored/<id>/preset.<ext>（优先于只读的 factory）；
     * - 导入预设原子覆盖 imported/<id>/preset.<ext>。
     */
    fun replaceForRestore(id: String, ext: String, bytes: ByteArray) {
        synchronized(lock) {
            if (BrushIdentity.isBuiltin(id)) {
                val dir = restoredDir(id)
                dir.mkdirs()
                dir.listFiles()?.filter { it.name.startsWith("preset.") }?.forEach {
                    it.setWritable(true)
                    it.delete()
                }
                val f = File(dir, "preset.$ext")
                AtomicFiles.write(f, bytes)
                f.setReadOnly()
            } else if (BrushIdentity.isImported(id)) {
                val dir = importedDir(id)
                dir.mkdirs()
                dir.listFiles()?.filter { it.name.startsWith("preset.") }?.forEach {
                    it.setWritable(true)
                    it.delete()
                }
                val f = File(dir, "preset.$ext")
                AtomicFiles.write(f, bytes)
                f.setReadOnly()
            }
        }
    }

    /** id → 快照文件；内置按 `builtin:<name>` 优先查 restored，再在当前出厂版本里找 */
    fun snapshotFile(id: String): File? = when {
        BrushIdentity.isBuiltin(id) -> restoredFile(id) ?: factoryFile(id.removePrefix(BrushIdentity.BUILTIN_PREFIX))
        BrushIdentity.isImported(id) -> importedFile(id)
        else -> null
    }

    fun read(id: String): ByteArray? = snapshotFile(id)?.readBytes()

    fun has(id: String): Boolean = snapshotFile(id) != null
}
